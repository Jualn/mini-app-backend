package cn.jualn.miniapp.module.article;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** Own disposable MySQL only: upgrade with historical references and empty-schema replay. */
@EnabledIfSystemProperty(named="event.test.jdbcUrl",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/article_contract_test")
class ArticleCategoryMigrationTest {
 @Test void upgradeAndReplayPreserveHistoryAndValidateRepeatExecution() {
  String url=System.getProperty("event.test.jdbcUrl");
  String server=url.substring(0,url.lastIndexOf('/')+1);
  var admin=new JdbcTemplate(new DriverManagerDataSource(server+"mysql?sslMode=DISABLED&allowPublicKeyRetrieval=true","root",""));
  for(String schema:new String[]{"article_category_upgrade_test","article_category_replay_test","article_contract_test"})
   assertEquals(0,admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",Integer.class,schema),"Dedicated schema must be fresh");
  for(String schema:new String[]{"article_category_upgrade_test","article_category_replay_test"}) {
   assertEquals(0,admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",Integer.class,schema),"Dedicated schema must be fresh");
   admin.execute("CREATE DATABASE "+schema);
   var source=new DriverManagerDataSource(server+schema+"?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root","");
   var jdbc=new JdbcTemplate(source);
   boolean upgrade=schema.contains("upgrade");
   if(upgrade) {
    Flyway.configure().dataSource(source).target("59").load().migrate();
    jdbc.update("INSERT INTO article_publisher VALUES(1,'Historical publisher',1,9,NOW(6))");
    jdbc.update("INSERT INTO article_category(id,name,display_order,enabled,revision,created_at,updated_at,created_by,updated_by) VALUES(700,'Historical category',7,0,5,NOW(6),NOW(6),9,9)");
    jdbc.update("INSERT INTO article(id,title,summary,body,plain_text,category_id,publisher_id,comments_enabled,publish_status,revision,created_by,updated_by,created_at,updated_at) VALUES(800,'Historical article','','{}','',700,1,1,'DRAFT',3,9,9,NOW(6),NOW(6))");
   }
   var flyway=Flyway.configure().dataSource(source).target("60").load();
   assertEquals(upgrade?1:60,flyway.migrate().migrationsExecuted);
   flyway.validate(); assertEquals(0,flyway.migrate().migrationsExecuted);
   if(upgrade) {
    assertNull(jdbc.queryForObject("SELECT parent_id FROM article_category WHERE id=700",Long.class));
    assertEquals("Historical category",jdbc.queryForObject("SELECT name FROM article_category WHERE id=700",String.class));
    assertEquals(7,jdbc.queryForObject("SELECT display_order FROM article_category WHERE id=700",Integer.class));
    assertEquals(0,jdbc.queryForObject("SELECT enabled FROM article_category WHERE id=700",Integer.class));
    assertEquals(5L,jdbc.queryForObject("SELECT revision FROM article_category WHERE id=700",Long.class));
    assertEquals(700L,jdbc.queryForObject("SELECT category_id FROM article WHERE id=800",Long.class));
    assertEquals(3L,jdbc.queryForObject("SELECT revision FROM article WHERE id=800",Long.class));
    assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("UPDATE article_category SET parent_id=999999 WHERE id=700"));
   }
   assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=? AND table_name='article_category' AND index_name='idx_article_category_parent'",Integer.class,schema));
  }
  assertEquals(0,admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='article_contract_test'",Integer.class));
  admin.execute("CREATE DATABASE article_contract_test");
  var source=new DriverManagerDataSource(url+"?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root","");
  Flyway.configure().dataSource(source).load().migrate();
  var jdbc=new JdbcTemplate(source);
  jdbc.update("INSERT INTO user_profile(openid) VALUES('m01-migration-legacy')");
  jdbc.update("INSERT INTO post(user_id,title,content,status) VALUES((SELECT id FROM user_profile WHERE openid='m01-migration-legacy'),'Historical post','Preserved',1)");
 }
}
