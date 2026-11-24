package cn.jualn.miniapp.module.points;

import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.authorization.service.RbacCatalog;

import cn.jualn.miniapp.config.JacksonConfig;
import cn.jualn.miniapp.common.exception.*;
import cn.jualn.miniapp.module.authorization.model.RbacModels.*;
import cn.jualn.miniapp.module.admin.auth.service.AdminTokenService;
import cn.jualn.miniapp.module.points.bo.PointsBO.*;
import cn.jualn.miniapp.module.points.dto.PointsRequests;
import cn.jualn.miniapp.module.points.service.*;
import cn.jualn.miniapp.module.points.vo.PointsVO;
import cn.jualn.miniapp.module.user.service.UserAccountService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PointsBoundaryTest {
    final com.fasterxml.jackson.databind.ObjectMapper json=new JacksonConfig().objectMapper(new Jackson2ObjectMapperBuilder());
    @Test void strictRequestsRejectCoercionUnknownIdentityAndInvalidDecision() throws Exception {
        var good=json.readValue("{\"reviewNote\":\"核准\",\"awardedPoints\":10,\"decision\":\"APPROVED\"}",PointsRequests.Review.class);
        assertEquals(10,good.awardedPoints());
        assertNull(json.readValue("{\"decision\":\"APPROVED\",\"awardedPoints\":1}",PointsRequests.Review.class).reviewNote());
        for(String value:List.of("0","11","-1","1.2","2147483648","\"3\"","null")) {
            assertThrows(Exception.class,()->json.readValue("{\"reviewNote\":\"核准\",\"awardedPoints\":"+value+",\"decision\":\"APPROVED\"}",PointsRequests.Review.class));
        }
        for(String injection:List.of("userId","organizationId","role","status","awardedPoints","balance")) {
            assertThrows(Exception.class,()->json.readValue("{\"title\":\"事项\",\"description\":\"说明\",\"fileIds\":[\"f\"],\""+injection+"\":1}",PointsRequests.Create.class));
        }
        for(String bad:List.of("{\"decision\":\"PENDING\",\"reviewNote\":\"x\"}","{\"decision\":\"REJECTED\",\"awardedPoints\":null,\"reviewNote\":\"x\"}","{\"decision\":\"REJECTED\",\"reviewNote\":\"　 \"}")) {
            assertThrows(Exception.class,()->json.readValue(bad,PointsRequests.Review.class));
        }
        for(String bad:List.of("null","\"\"","\" \"")) assertThrows(Exception.class,()->json.readValue("{\"decision\":\"REJECTED\",\"reviewNote\":"+bad+"}",PointsRequests.Review.class));
        assertEquals("VIDEO",json.readValue("{\"files\":[{\"fileName\":\"x.mp4\",\"kind\":\"VIDEO\",\"contentType\":\"video/mp4\",\"sizeBytes\":10}]}",PointsRequests.Upload.class).files().get(0).kind());
        assertThrows(Exception.class,()->json.readValue("{\"reason\":\"r\"}",PointsRequests.Reversal.class));
        assertEquals(1,json.readValue("{\"correctedPoints\":1,\"reason\":\"r\",\"basis\":\"b\"}",PointsRequests.Correction.class).correctedPoints());
        assertThrows(Exception.class,()->json.readValue("{\"files\":[{\"fileName\":\"x.pdf\",\"kind\":\"PDF\",\"contentType\":\"image/png\",\"sizeBytes\":10}]}",PointsRequests.Upload.class));
    }
    @Test void exactSafeAmountsAndNumericWireUnderLegacyGlobalLongSerializer() throws Exception {
        var now=LocalDateTime.of(2026,10,18,1,2,3,123456000);
        var totals=new Totals(BigDecimal.valueOf(PointsAmounts.MAX),BigDecimal.ONE);
        var result=PointsAmounts.summary("arithmetic-test-person",totals,now);
        var wire=json.readTree(json.writeValueAsString(PointsVO.summary(result)));
        assertTrue(wire.get("balance").isIntegralNumber());assertTrue(wire.get("totalGranted").isIntegralNumber());
        assertEquals("arithmetic-test-person",wire.get("personId").textValue());assertTrue(wire.get("evaluatedAt").textValue().endsWith("Z"));
        assertEquals("/problems/points-overflow",assertThrows(ContractProblemException.class,()->PointsAmounts.adding("arithmetic-test-person",totals,1,now)).getType());
        assertThrows(ContractProblemException.class,()->PointsAmounts.checked(new BigDecimal("1.1")));
        assertEquals("/problems/points-negative-balance",assertThrows(ContractProblemException.class,()->PointsAmounts.summary("arithmetic-test-person",new Totals(BigDecimal.ZERO,BigDecimal.ONE),now)).getType());
    }
    @Test void namespaceAndIndependentReversalCapabilitiesAreMandatory() {
        CurrentAuthorization auth=mock(CurrentAuthorization.class);var catalog=mock(RbacCatalog.class);
        var tokens=mock(AdminTokenService.class);when(tokens.requireAuthenticatedSession()).thenReturn(9L);
        var policy=new UnavailablePointsDependencies(auth,catalog,mock(UserAccountService.class),tokens);
        when(catalog.supports(anyString(),anySet())).thenReturn(true);
        var now=LocalDateTime.now();
        var wrong=new Grant("a","ANY","ACTIVITY","GLOBAL",List.of("points:read","points:review"),null);
        var scopeOnly=new Grant("b","ANY","POINTS","GLOBAL",List.of(),null);
        when(auth.current(9)).thenReturn(new Decision(9,Source.ASSIGNMENT,"v","c",now,true,List.of(),List.of(wrong,scopeOnly)));
        assertThrows(BusinessException.class,()->policy.requireAdmin(9,Action.REVIEW));
        var reviewer=new Grant("a","POINTS_REVIEWER","POINTS","GLOBAL",List.of("points:read","points:review"),null);
        when(auth.current(9)).thenReturn(new Decision(9,Source.ASSIGNMENT,"v","c",now,true,List.of(),List.of(reviewer)));
        policy.requireAdmin(9,Action.REVIEW);
        assertEquals(9,policy.actor(Action.REVIEW));verify(tokens).requireAuthenticatedSession();
        assertThrows(BusinessException.class,()->policy.requireAdmin(9,Action.REVERSE));
        when(auth.current(9)).thenReturn(new Decision(9,Source.LEGACY,"v","c",now,true,List.of("*"),List.of(reviewer)));
        assertThrows(BusinessException.class,()->policy.requireAdmin(9,Action.READ));
        assertEquals("BUSINESS_ELIGIBILITY",assertThrows(ContractProblemException.class,()->policy.eligible(42)).getMessage());
        assertEquals("PERSON_RESOLVER",assertThrows(ContractProblemException.class,()->policy.subject(42)).getMessage());
        assertEquals("PRIVATE_MEDIA",assertThrows(ContractProblemException.class,()->policy.bindFiles(42,1,List.of("f"))).getMessage());
        when(tokens.requireAuthenticatedSession()).thenThrow(new IllegalStateException("Synthetic session store failure"));
        assertEquals("AUTHORIZATION",assertThrows(ContractProblemException.class,()->policy.actor(Action.READ)).getMessage());
    }
}
