package cn.jualn.miniapp.module.audit.async;
import cn.jualn.miniapp.module.audit.service.*;
import cn.jualn.miniapp.module.media.service.*;
import cn.jualn.miniapp.module.user.service.UserWechatIdentityService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AuditPrivateMediaJobTest {
 @Test void signingFailureIsBeforeInvocationAndDoesNotBecomeUnknownRemoteOutcome() {
  var audits=mock(AuditService.class);var users=mock(UserWechatIdentityService.class);var execution=mock(ContentSafetyCheckExecution.class);var source=mock(MediaSafetySource.class);var provider=mock(org.springframework.beans.factory.ObjectProvider.class);when(provider.getObject()).thenReturn(source);
  var handler=new AuditMediaJobHandler(audits,users);org.springframework.test.util.ReflectionTestUtils.setField(handler,"execution",execution);org.springframework.test.util.ReflectionTestUtils.setField(handler,"mediaSources",provider);when(execution.prepare(1)).thenReturn(1L);
  String url="https://api.example/v1/media/files/2/content";when(source.resolve(url)).thenThrow(new IllegalStateException("private storage unavailable"));
  var payload=new AuditMediaJobPayload(1L,cn.jualn.miniapp.module.audit.enums.AuditScene.POST,3L,url,cn.jualn.miniapp.module.media.enums.MediaType.IMAGE,3,4L);
  assertThrows(IllegalStateException.class,()->handler.handle(payload));verify(execution).failedBeforeInvocation(1);verify(execution,never()).unknown(anyLong());verifyNoInteractions(audits);
 }
}
