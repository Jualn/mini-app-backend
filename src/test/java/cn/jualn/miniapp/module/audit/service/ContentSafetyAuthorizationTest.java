package cn.jualn.miniapp.module.audit.service;

import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.authorization.service.RbacAuthorizationReader;
import cn.jualn.miniapp.module.authorization.model.UnifiedModels;
import cn.jualn.miniapp.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import static cn.jualn.miniapp.module.authorization.model.RbacModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ContentSafetyAuthorizationTest {
    final RbacAuthorizationReader reader=mock(RbacAuthorizationReader.class);
    final ContentSafetyAuthorization access=new ContentSafetyAuthorization(new CurrentAuthorization(reader));
    Grant grant(String id,String duty,String space,String target,List<String> permissions) {return new Grant(id,"CONTENT_REVIEWER",space,"GLOBAL",permissions,null,duty,target==null?null:new UnifiedModels.Target(target,null,"ORGANIZATION".equals(target)?"o":null));}
    void use(List<Grant> grants) {when(reader.read(9)).thenReturn(new Decision(9,Source.ASSIGNMENT,"v","c",LocalDateTime.now(),true,List.of(),grants));}
    @Test void exactDutyPlatformAndSingleSourceAreAllNecessary() {
        var both=List.of("review:read","review:decide");
        for(String space:List.of("POST","ACTIVITY","PUBLIC_EVENT")) {
            String duty=ContentSafetyAuthorization.duty(space);
            use(List.of(grant("a",duty,space,"PLATFORM",both)));assertDoesNotThrow(()->access.require(9,space,true,false));
            use(List.of(grant("a",null,space,null,both)));assertThrows(BusinessException.class,()->access.require(9,space,true,false));
            use(List.of(grant("a",duty,space,"ORGANIZATION",both)));assertThrows(BusinessException.class,()->access.require(9,space,true,false));
            use(List.of(grant("a",duty,space,"PLATFORM",List.of("review:read")),grant("b",duty,space,"PLATFORM",List.of("review:decide"))));assertThrows(BusinessException.class,()->access.require(9,space,true,false));
        }
    }
    @Test void providerFailureUnknownAndReviewAreDifferentFacts() {
        var json=new ObjectMapper();
        assertEquals("review",ContentSafetyResults.interpret(json,"{\"errcode\":0,\"result\":{\"suggest\":\"risky\"}}").result());
        assertEquals("review",ContentSafetyResults.interpret(json,"{\"errcode\":0,\"result\":{\"suggest\":\"reject\"}}").result());
        for(String raw:List.of("", "null", "broken", "{}","{\"errcode\":1,\"result\":{\"suggest\":\"pass\"}}","{\"errcode\":0,\"result\":{\"suggest\":\"other\"}}","{\"errcode\":0,\"result\":{\"suggest\":\"pass\"},\"detail\":[{\"errcode\":0,\"suggest\":\"risky\"}]}")) assertEquals("unknown",ContentSafetyResults.interpret(json,raw).result());
    }
}
