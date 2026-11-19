package cn.jualn.miniapp.config;

import cn.dev33.satoken.exception.SaJsonConvertException;
import cn.dev33.satoken.json.SaJsonTemplate;
import cn.dev33.satoken.json.SaJsonTemplateForJackson;
import cn.dev33.satoken.session.SaSession;
import cn.jualn.miniapp.module.user.enums.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

class SaTokenSessionCompatibilityTest {
    private static final String LEGACY_ROLE = "cn.jualn.miniapp.common.enums.UserRole";

    @Test
    void loginCanReuseAnExistingLegacySession() {
        var previousJson = cn.dev33.satoken.SaManager.getSaJsonTemplate();
        var previousDao = cn.dev33.satoken.SaManager.getSaTokenDao();
        var serializer = new cn.dev33.satoken.serializer.impl.SaSerializerTemplateForJson();
        var dao = new cn.dev33.satoken.dao.SaTokenDaoDefaultImpl() {
            @Override
            public <T> T getObject(String key, Class<T> type) {
                Object stored = super.getObject(key);
                return stored instanceof String value
                        ? serializer.stringToObject(value, type) : type.cast(stored);
            }
        };
        try {
            cn.dev33.satoken.SaManager.setSaJsonTemplate(new SaTokenConfig().saJsonTemplate());
            cn.dev33.satoken.SaManager.setSaTokenDao(dao);
            cn.dev33.satoken.context.mock.SaTokenContextMockUtil.setMockContext();
            var logic = new cn.dev33.satoken.stp.StpLogic("legacy-session-test");
            String key = logic.splicingKeySession(42L);
            String legacy = sessionJson(UserRole.ADMIN)
                    .replace("synthetic-session", key).replace(UserRole.class.getName(), LEGACY_ROLE);
            dao.setObject(key, legacy, 300);
            logic.login(42L);
            assertEquals(42L, logic.getLoginIdAsLong());
            assertEquals(UserRole.ADMIN, logic.getSession().get("role"));
            assertFalse(logic.getSession().getTerminalList().isEmpty());
        } finally {
            cn.dev33.satoken.context.mock.SaTokenContextMockUtil.clearContext();
            cn.dev33.satoken.SaManager.setSaJsonTemplate(previousJson);
            cn.dev33.satoken.SaManager.setSaTokenDao(previousDao);
            dao.destroy();
        }
    }

    @Test
    void legacyRolesAreReadAndRewrittenWithCurrentType() {
        new ApplicationContextRunner().withUserConfiguration(SaTokenConfig.class).run(context -> {
            assertNull(context.getStartupFailure());
            SaJsonTemplate json = context.getBean(SaJsonTemplate.class);
            for (UserRole role : UserRole.values()) {
                String legacy = sessionJson(role).replace(UserRole.class.getName(), LEGACY_ROLE);
                assertTrue(legacy.contains(LEGACY_ROLE));
                // Reproduce the failure with the uncustomized library converter.
                assertThrows(SaJsonConvertException.class,
                        () -> new SaJsonTemplateForJackson().jsonToObject(legacy, SaSession.class));
                SaSession restored = json.jsonToObject(legacy, SaSession.class);
                assertEquals(role, restored.get("role"));
                assertEquals("synthetic-session", restored.getId());
                assertEquals(42L, restored.getLoginId());
                String rewritten = json.objectToJson(restored);
                assertFalse(rewritten.contains(LEGACY_ROLE));
                assertEquals(role, json.jsonToObject(rewritten, SaSession.class).get("role"));
            }
        });
    }

    @Test
    void unknownTypesAndInvalidRolesStillFail() {
        SaJsonTemplate json = new SaTokenConfig().saJsonTemplate();
        String current = sessionJson(UserRole.USER);
        assertThrows(SaJsonConvertException.class, () -> json.jsonToObject(
                current.replace(UserRole.class.getName(), "cn.jualn.miniapp.unknown.Role"), SaSession.class));
        assertThrows(SaJsonConvertException.class, () -> json.jsonToObject(
                current.replace(UserRole.class.getName(), LEGACY_ROLE).replace("\"USER\"", "\"ROOT\""), SaSession.class));
    }

    @Test
    void currentSessionsKeepTheirRepresentation() {
        SaJsonTemplate json = new SaTokenConfig().saJsonTemplate();
        String current = sessionJson(UserRole.ADMIN);
        assertEquals(UserRole.ADMIN, json.jsonToObject(current, SaSession.class).get("role"));
    }

    private String sessionJson(UserRole role) {
        SaSession session = new SaSession();
        session.setId("synthetic-session");
        session.setLoginId(42L);
        var data = new HashMap<String, Object>();
        data.put("role", role);
        session.setDataMap(data);
        return new SaJsonTemplateForJackson().objectToJson(session);
    }
}
