package cn.jualn.miniapp.module.article;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="event.test.jdbcUrl",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/article_contract_test")
class ArticleOrganizationMigrationTest {
 @Test void upgradeAndEmptyReplayPreserveUnmappedHistoryAndInteractions() {
  String url=System.getProperty("event.test.jdbcUrl"),server=url.substring(0,url.lastIndexOf('/')+1);
  var admin=new JdbcTemplate(new DriverManagerDataSource(server+"mysql?sslMode=DISABLED&allowPublicKeyRetrieval=true","root",""));
  for(String name:new String[]{"article_org_upgrade_test","article_contract_test"}) assertEquals(0,admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",Integer.class,name));
  for(String name:new String[]{"article_org_upgrade_test","article_contract_test"}) {
   admin.execute("CREATE DATABASE "+name);
   var source=new DriverManagerDataSource(server+name+"?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root","");var db=new JdbcTemplate(source);
   boolean upgrade=name.contains("upgrade");String articleBefore=null,commentBefore=null;
   if(upgrade) {
    Flyway.configure().dataSource(source).target("60").load().migrate();
    db.update("INSERT INTO user_profile(id,openid,status) VALUES(9,'article-upgrade',1)");
    db.update("INSERT INTO article_publisher VALUES(1,'Legacy visible label',1,9,NOW(6))");
    db.update("INSERT INTO article_category(id,name,display_order,enabled,created_by,updated_by,created_at,updated_at) VALUES(900,'Legacy category',0,1,9,9,NOW(6),NOW(6))");
    db.update("INSERT INTO article(id,title,summary,body,plain_text,category_id,publisher_id,publish_status,revision,created_by,updated_by,created_at,updated_at,published_at,last_published_at) VALUES(900,'Legacy article','','{}','Legacy',900,1,'PUBLISHED',7,9,9,NOW(6),NOW(6),NOW(6),NOW(6))");
    db.update("INSERT INTO like_record(user_id,target_type,target_id) VALUES(9,8,900)");
    db.update("INSERT INTO comment(user_id,target_type,target_id,content) VALUES(9,8,900,'Preserved comment')");
    articleBefore=db.queryForObject("SELECT SHA2(CAST(JSON_ARRAY(id,title,body,category_id,publisher_id,revision,publish_status,published_at) AS CHAR),256) FROM article WHERE id=900",String.class);
    commentBefore=db.queryForObject("SELECT SHA2(CAST(JSON_ARRAY(id,user_id,target_type,target_id,content) AS CHAR),256) FROM comment WHERE target_type=8",String.class);
   }
   var flyway=Flyway.configure().dataSource(source).load();assertEquals(upgrade?1:61,flyway.migrate().migrationsExecuted);flyway.validate();assertEquals(0,flyway.migrate().migrationsExecuted);
   assertEquals("YES",db.queryForObject("SELECT IS_NULLABLE FROM information_schema.columns WHERE table_schema=? AND table_name='article' AND column_name='publisher_id'",String.class,name));
   assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM article_write_guard",Integer.class));
   assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM article_publisher_organization_mapping",Integer.class));
   if(upgrade) {
    assertEquals(articleBefore,db.queryForObject("SELECT SHA2(CAST(JSON_ARRAY(id,title,body,category_id,publisher_id,revision,publish_status,published_at) AS CHAR),256) FROM article WHERE id=900",String.class));
    assertEquals(commentBefore,db.queryForObject("SELECT SHA2(CAST(JSON_ARRAY(id,user_id,target_type,target_id,content) AS CHAR),256) FROM comment WHERE target_type=8",String.class));
    assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM like_record WHERE target_type=8 AND target_id=900",Integer.class));
    assertNull(db.queryForObject("SELECT publisher_org_id FROM article WHERE id=900",String.class));assertNull(db.queryForObject("SELECT publisher_name_snapshot FROM article WHERE id=900",String.class));
   } else {
    db.update("INSERT INTO user_profile(openid) VALUES('m01-migration-legacy')");
    db.update("INSERT INTO post(user_id,title,content,status) VALUES((SELECT id FROM user_profile WHERE openid='m01-migration-legacy'),'Historical post','Preserved',1)");
   }
  }
 }
}
