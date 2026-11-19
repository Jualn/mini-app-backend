package cn.jualn.miniapp.module.audit.service.impl;
import cn.jualn.miniapp.module.audit.enums.AuditScene;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.audit.entity.ContentAuditLog;
import cn.jualn.miniapp.module.audit.enums.AuditStatus;
import cn.jualn.miniapp.module.audit.mapper.ContentAuditLogMapper;
import cn.jualn.miniapp.module.audit.service.ArticleCandidateAuditCallback;
import cn.jualn.miniapp.module.wx.dto.WxaMediaCheckMessage;
import cn.jualn.miniapp.third.wx.client.WxClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ArticleMediaCallbackTest {
 final ContentAuditLogMapper logs=mock(ContentAuditLogMapper.class);
 final AuditResultPersistenceService persistence=mock(AuditResultPersistenceService.class);
 final AuditServiceImpl audit=new AuditServiceImpl(mock(WxClient.class),logs,mock(RedisService.class),new ObjectMapper(),persistence);
 WxaMediaCheckMessage callback(String suggest,Integer error) {
  when(logs.selectOne(any())).thenReturn(ContentAuditLog.builder().id(3L).targetId(7L).targetType(AuditScene.ARTICLE.getCode()).build());
  var message=new WxaMediaCheckMessage(); message.setTraceId("synthetic-article-trace"); message.setErrCode(error);
  if(suggest!=null) { var result=new WxaMediaCheckMessage.Result(); result.setSuggest(suggest); message.setResult(result); }
  return message;
 }
 @Test void missingReviewPartialPassAndProviderErrorCannotApproveAnArticleCandidate() {
  for(String suggest:new String[]{null,"unknown","review"}) audit.handleWxMediaCallback(callback(suggest,0));
  audit.handleWxMediaCallback(callback("pass",1)); audit.handleWxMediaCallback(callback("pass",null));
  var partial=callback(null,0); var detail=new WxaMediaCheckMessage.Detail(); detail.setErrCode(0); detail.setSuggest("pass"); partial.setDetail(java.util.List.of(detail)); audit.handleWxMediaCallback(partial);
  verifyNoInteractions(persistence);
 }
 @Test void definitiveResultIsPersistedButCandidateCallbackHasNoPublishingAuthority() {
  audit.handleWxMediaCallback(callback("pass",0));
  verify(persistence).completeMedia(eq(3L),eq(AuditScene.ARTICLE),eq(7L),eq("synthetic-article-trace"),eq(AuditStatus.PASS),anyString(),isNull());
  var candidate=new ArticleCandidateAuditCallback();
  assertSame(candidate,new cn.jualn.miniapp.config.AuditConfig().callbackRegister(java.util.List.of(candidate)).get(AuditScene.ARTICLE));
  candidate.onPass(7L,3L); candidate.onReject(7L,3L,"late");
 }
}
