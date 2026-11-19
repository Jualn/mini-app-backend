package cn.jualn.miniapp.module.person;

import cn.jualn.miniapp.module.person.service.facts.PersonCatalog;
import cn.jualn.miniapp.module.authorization.service.RbacCatalog;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class PointsIntegrationCatalogTest {
    @Test void confirmedPolicyAndImplementedSupportNeedNoIntegrationProfileOrDatabaseNaming() {
        for (String profile : java.util.List.of("dev", "prod", "points-integration")) {
            var environment = new MockEnvironment().withProperty("spring.datasource.url", "jdbc:mysql://db.example:3306/campus");
            environment.setActiveProfiles(profile);
            new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                    .withInitializer(context -> context.setEnvironment(environment))
                    .withBean(PersonCatalog.class).withBean(RbacCatalog.class).run(context -> {
            assertNull(context.getStartupFailure());
            var people = context.getBean(PersonCatalog.class);
            var rbac = context.getBean(RbacCatalog.class);
            assertEquals("CONFIRMED", people.value().policyConfirmationStatus());
            assertTrue(people.pointsPolicyConfirmed());
            for (var stage : people.value().stages()) assertTrue(people.pointsEligibleStage(stage.stageId()));
            assertFalse(people.pointsEligibleStage("UNKNOWN_STAGE"));
            assertEquals("CONFIRMED_LOCAL_PARTY_V1", people.pointsPolicySource());
            assertTrue(rbac.supports("POINTS", Set.of("points:read", "points:review")));
            assertFalse(rbac.supports("POINTS", Set.of("points:rule-manage")));
            assertFalse(rbac.supports("IDENTITY", Set.of("points:read")));
                    });
        }
    }
}
