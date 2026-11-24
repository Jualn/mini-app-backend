package cn.jualn.miniapp.module.article;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfSystemProperty(named="event.test.jdbcUrl",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/article_contract_test")
class ArticleContentMigrationTest {
 @Test void upgradeAndReplayPreserveOldContentWithoutInventingMappings() {
  String url=System.getProperty("event.test.jdbcUrl");String server=url.substring(0,url.lastIndexOf('/')+1);
  var admin=new JdbcTemplate(new DriverManagerDataSource(server+"mysql?sslMode=DISABLED&allowPublicKeyRetrieval=true","root",""));
  for(String schema:new String[]{"article_content_upgrade_test_"+java.util.UUID.randomUUID().toString().replace("-",""),"article_contract_test"}) {
   assertEquals(0,admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",Integer.class,schema),"Fresh schema on this round's exclusive instance required");
   admin.execute("CREATE DATABASE "+schema);
   var source=new DriverManagerDataSource(server+schema+"?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root","");var db=new JdbcTemplate(source);
   boolean upgrade=schema.contains("upgrade");
   if(upgrade) {
    Flyway.configure().dataSource(source).target("82").load().migrate();
    db.update("INSERT INTO article_publisher VALUES(1,'Retained publisher',1,9,NOW(6))");
    db.update("INSERT INTO article_category(id,name,display_order,enabled,created_at,updated_at,created_by,updated_by) VALUES(1,'Old category',0,1,NOW(6),NOW(6),9,9)");
    db.update("INSERT INTO article(id,title,summary,body,plain_text,category_id,publisher_id,created_by,updated_by,created_at,updated_at) VALUES(1,'Retained title','','{}','Old text',1,1,9,9,NOW(6),NOW(6))");
   }
   var migration=Flyway.configure().dataSource(source).load();assertEquals(upgrade?1:83,migration.migrate().migrationsExecuted);migration.validate();assertEquals(0,migration.migrate().migrationsExecuted);
   if(upgrade) {
    assertEquals("Old text",db.queryForObject("SELECT plain_text FROM article WHERE id=1",String.class));
    for(String column:new String[]{"attachment_ids","source","deleted_at","deleted_by"}) assertNull(db.queryForObject("SELECT "+column+" FROM article WHERE id=1",Object.class));
    assertNull(db.queryForObject("SELECT scene_codes FROM article_category WHERE id=1",String.class));
    var error=assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("UPDATE article SET deleted_at=NOW(6) WHERE id=1"));
    assertEquals(3819,((java.sql.SQLException)error.getRootCause()).getErrorCode());
   } else {
    db.update("INSERT INTO user_profile(openid) VALUES('m01-migration-legacy')");
    db.update("INSERT INTO post(user_id,title,content,status) VALUES((SELECT id FROM user_profile WHERE openid='m01-migration-legacy'),'Historical post','Preserved',1)");
   }
  }
 }
}
