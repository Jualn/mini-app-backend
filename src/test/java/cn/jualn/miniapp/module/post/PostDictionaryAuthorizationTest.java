package cn.jualn.miniapp.module.post;

import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.authorization.service.RbacAuthorizationReader;

import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.stp.StpUtil;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.module.authorization.model.RbacModels.*;
import cn.jualn.miniapp.module.post.service.PostDictionaryAuthorization;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostDictionaryAuthorizationTest {
    @Test void independentAdminIdentityAndCurrentScopedPermissionsAreRequired() {
        var reader=mock(RbacAuthorizationReader.class);
        var auth=new PostDictionaryAuthorization(new CurrentAuthorization(reader));
        SaTokenContextMockUtil.setMockContext();
        try {
            when(reader.read(9)).thenReturn(decision("POST",List.of("post-dictionary:read")));
            StpUtil.switchTo(9L);
            assertThrows(NotLoginException.class,auth::read);
            AdminStpUtil.STP_LOGIC.switchTo(9L);
            assertDoesNotThrow(auth::read);
            assertThrows(BusinessException.class,()->auth.write(9));
            when(reader.read(9)).thenReturn(decision("POST",List.of("post-dictionary:manage")));
            assertDoesNotThrow(()->auth.write(9));
            assertThrows(BusinessException.class,auth::read);
            when(reader.read(8)).thenReturn(decision("POST",List.of("post-dictionary:manage")));
            assertThrows(BusinessException.class,()->auth.write(8));
            when(reader.read(9)).thenReturn(decision("ACTIVITY",List.of("post-dictionary:manage")));
            assertThrows(BusinessException.class,()->auth.write(9));
            when(reader.read(9)).thenReturn(decision("POST",List.of()));
            assertThrows(BusinessException.class,auth::read);
        } finally {SaTokenContextMockUtil.clearContext();}
    }
    private Decision decision(String namespace,List<String> permissions) {
        return new Decision(9,Source.ASSIGNMENT,"v","c",LocalDateTime.now(),true,List.of(),
                List.of(new Grant("a","POST_DICTIONARY_ADMIN",namespace,"GLOBAL",permissions,null)));
    }
}
