package cn.jualn.miniapp.module.authorization.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Only the task-owned disposable MySQL. No clean, DROP DATABASE, or shared-container data reset. */
@EnabledIfSystemProperty(named="unified.r3.jdbcUrl",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/unified_r3_migration_test")
class UnifiedR3MigrationTest {
    DriverManagerDataSource source(String url) {return new DriverManagerDataSource(url+"?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true","root","");}
    @Test void emptyReplayAndUpgradePreserveEveryOldDefinitionMemberAssignmentAndAudit() {
        String url=System.getProperty("unified.r3.jdbcUrl"),server=url.substring(0,url.lastIndexOf('/')+1);
        var admin=new JdbcTemplate(source(server+"mysql"));
        for(boolean upgrade:new boolean[]{false,true}) {
            String name="unified_r3_"+(upgrade?"upgrade_":"empty_")+UUID.randomUUID().toString().replace("-","");
            admin.execute("CREATE DATABASE "+name);var ds=source(server+name);var db=new JdbcTemplate(ds);
            if(upgrade) Flyway.configure().dataSource(ds).target("61").load().migrate();
            else Flyway.configure().dataSource(ds).load().migrate();
            if(upgrade) {
                db.update("UPDATE rbac_role SET lifecycle='ENABLED',readiness='READY',grantable=1,unavailable_reason=NULL WHERE code='NOTICE_MONITOR'");
                db.update("UPDATE rbac_permission_definition SET lifecycle='ENABLED',readiness='READY',grantable=1,unavailable_reason=NULL WHERE code='notice:read'");
            }
            var oldRoles=db.queryForList("SELECT code,name,namespaces,lifecycle,readiness,grantable,unavailable_reason FROM rbac_role ORDER BY code");
            var oldMembers=db.queryForList("SELECT role_code,permission_code FROM rbac_role_permission ORDER BY role_code,permission_code");
            var oldPermissions=db.queryForList("SELECT code,description,namespaces,lifecycle,readiness,grantable,unavailable_reason FROM rbac_permission_definition ORDER BY code");
            db.update("INSERT INTO user_profile(id,openid,role,status) VALUES(91001,?,1,1)",name);
            db.update("INSERT INTO rbac_authorization_authority VALUES(91001,'ASSIGNMENT',UUID(),UTC_TIMESTAMP(6))");
            String assignment=UUID.randomUUID().toString(),change=UUID.randomUUID().toString();
            db.update("INSERT INTO rbac_user_role_assignment(id,user_id,role_code,namespace,scope_type,granted_at,version,grant_change_id) VALUES(?,91001,'NOTICE_MONITOR','NOTIFICATION','GLOBAL',UTC_TIMESTAMP(6),UUID(),?)",assignment,change);
            db.update("INSERT INTO rbac_authorization_change(id,operation_id,actor_user_id,target_user_id,assignment_id,action,idempotency_key,request_fingerprint,reason,snapshot_version,before_snapshot,after_snapshot,receipt,occurred_at) VALUES(?,UUID(),91001,91001,?,'GRANT',?,'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','synthetic preserved history',1,NULL,JSON_OBJECT('fixture',true),JSON_OBJECT('fixture',true),UTC_TIMESTAMP(6))",change,assignment,UUID.randomUUID().toString());
            var assignments=db.queryForList("SELECT * FROM rbac_user_role_assignment");var audit=db.queryForList("SELECT id,operation_id,HEX(idempotency_key) command_key,CAST(after_snapshot AS CHAR) after_snapshot,CAST(receipt AS CHAR) receipt,occurred_at FROM rbac_authorization_change");var authority=db.queryForList("SELECT * FROM rbac_authorization_authority");
            var flyway=Flyway.configure().dataSource(ds).load();assertEquals(upgrade?1:0,flyway.migrate().migrationsExecuted);flyway.validate();assertEquals(0,flyway.migrate().migrationsExecuted);
            assertEquals(oldPermissions,db.queryForList("SELECT code,description,namespaces,lifecycle,readiness,grantable,unavailable_reason FROM rbac_permission_definition ORDER BY code"));
            assertTrue(db.queryForList("SELECT code,name,namespaces,lifecycle,readiness,grantable,unavailable_reason FROM rbac_role ORDER BY code").containsAll(oldRoles));
            assertTrue(db.queryForList("SELECT role_code,permission_code FROM rbac_role_permission ORDER BY role_code,permission_code").containsAll(oldMembers));
            assertEquals(assignments,db.queryForList("SELECT * FROM rbac_user_role_assignment"));assertEquals(audit,db.queryForList("SELECT id,operation_id,HEX(idempotency_key) command_key,CAST(after_snapshot AS CHAR) after_snapshot,CAST(receipt AS CHAR) receipt,occurred_at FROM rbac_authorization_change"));assertEquals(authority,db.queryForList("SELECT * FROM rbac_authorization_authority"));
            assertEquals(25,db.queryForObject("SELECT COUNT(*) FROM rbac_role",Integer.class));assertEquals(77,db.queryForObject("SELECT COUNT(*) FROM rbac_permission_definition",Integer.class));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM rbac_role WHERE code IN ('ARTICLE_CATEGORY_STEWARD_V1','POST_DICTIONARY_STEWARD_V1','POST_MODERATOR_V1') AND grantable=1",Integer.class));
            assertEquals(new RbacCatalog().digest(),db.queryForObject("SELECT DISTINCT catalog_digest FROM rbac_role",String.class));
        }
    }
}
