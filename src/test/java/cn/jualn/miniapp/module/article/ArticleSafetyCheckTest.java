package cn.jualn.miniapp.module.article;
import cn.jualn.miniapp.common.exception.*;
import cn.jualn.miniapp.module.article.service.ArticleSafetyChecks;
import cn.jualn.miniapp.module.audit.service.*;
import cn.jualn.miniapp.module.audit.mapper.ContentAuditLogMapper;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import cn.jualn.miniapp.module.user.service.UserWechatIdentityService;
import cn.jualn.miniapp.third.wx.client.WxClient;
import cn.jualn.miniapp.third.wx.dto.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ArticleSafetyCheckTest {
 final WxClient wx=mock(WxClient.class); final UserWechatIdentityService users=mock(UserWechatIdentityService.class);
 final AuditReservationService reservations=mock(AuditReservationService.class);
 final VideoSafetyChecks videos=mock(VideoSafetyChecks.class);
 final ArticleSafetyChecks checks=new ArticleSafetyCheckService(wx,users,reservations,mock(ContentAuditLogMapper.class),videos);
 WxMsgSecCheckResponse decision(String result) { var r=new WxMsgSecCheckResponse(); r.setErrcode(0); var info=new WxMsgSecCheckResponse.ResultInfo(); info.setSuggest(result); r.setResult(info); return r; }
 @Test void onlyExplicitCompleteProviderPassCanAuthorizeText() {
  when(users.getMiniOpenid(9L)).thenReturn("synthetic-openid");
  when(wx.msgSecCheck(any())).thenReturn(decision("pass")); assertDoesNotThrow(()->checks.text(9,"正文"));
  for(String suggest:List.of("risky","reject","review")) {
   when(wx.msgSecCheck(any())).thenReturn(decision(suggest)); var failure=assertThrows(ContractProblemException.class,()->checks.text(9,"正文"));
   assertEquals("review".equals(suggest)?503:409,failure.getStatus().value());
  }
  var incomplete=decision("pass"); incomplete.setErrcode(null); when(wx.msgSecCheck(any())).thenReturn(incomplete);
  assertEquals(503,assertThrows(ContractProblemException.class,()->checks.text(9,"正文")).getStatus().value());
  var contradictory=decision("pass"); var detail=new WxMsgSecCheckResponse.DetailInfo(); detail.setErrcode(0); detail.setSuggest("risky"); contradictory.setDetail(List.of(detail)); when(wx.msgSecCheck(any())).thenReturn(contradictory);
  assertEquals(503,assertThrows(ContractProblemException.class,()->checks.text(9,"正文")).getStatus().value());
 }
 @Test void keywordNoHitRequiresRawProviderStructureVerification() {
  when(users.getMiniOpenid(9L)).thenReturn("synthetic-openid");
  var response=decision("pass");
  var keyword=new WxMsgSecCheckResponse.DetailInfo();
  keyword.setStrategy("keyword");
  keyword.setErrcode(0);
  var model=new WxMsgSecCheckResponse.DetailInfo();
  model.setStrategy("content_model");
  model.setErrcode(0);
  model.setSuggest("pass");
  response.setDetail(List.of(keyword,model));
  when(wx.msgSecCheck(any())).thenReturn(response);

  assertEquals(503,assertThrows(ContractProblemException.class,()->checks.text(9,"分类")).getStatus().value());
  response.setVerifiedEmptyKeywordPassStructure(true);
  assertDoesNotThrow(()->checks.text(9,"分类"));
 }
 @Test void missingTrustedIdentityAndLostProviderResponseFailClosed() {
  assertEquals(503,assertThrows(ContractProblemException.class,()->checks.text(9,"正文")).getStatus().value()); verifyNoInteractions(wx);
  when(users.getMiniOpenid(9L)).thenReturn("synthetic-openid");
  when(wx.msgSecCheck(any())).thenThrow(new ExternalServiceException(cn.jualn.miniapp.common.result.ResultCode.EXTERNAL_SERVICE_ERROR,"wechat","synthetic lost response"));
  assertEquals(503,assertThrows(ContractProblemException.class,()->checks.text(9,"正文")).getStatus().value());
 }
 @Test void boundedTextChecksPreserveAllUnicodeCodePoints() {
  when(users.getMiniOpenid(9L)).thenReturn("synthetic-openid"); when(wx.msgSecCheck(any())).thenReturn(decision("pass"));
  String content="😀".repeat(2005); checks.text(9,content);
  var capture=org.mockito.ArgumentCaptor.forClass(WxMsgSecCheckRequest.class); verify(wx,times(2)).msgSecCheck(capture.capture());
  assertEquals(content,capture.getAllValues().stream().map(WxMsgSecCheckRequest::getContent).collect(java.util.stream.Collectors.joining()));
  assertTrue(capture.getAllValues().stream().allMatch(r->r.getContent().codePointCount(0,r.getContent().length())<=2000));
 }
 @Test void videoIsNotApprovedByImageOrTextChecksAndLateCallbacksCannotPublish() {
  var video=MediaAttachmentBO.builder().kind("VIDEO").verified(true).objectKey("article/retained/video").build();
  doThrow(ArticleSafetyChecks.unavailable()).when(videos).requirePassed(video,9L);
  assertEquals(503,assertThrows(ContractProblemException.class,()->checks.media(9,1,List.of(video))).getStatus().value()); verifyNoInteractions(reservations,wx);
  var callback=new ArticleCandidateAuditCallback(); assertDoesNotThrow(()->callback.onPass(1L,2L)); assertDoesNotThrow(()->callback.onReject(1L,2L,"late"));
 }
 @Test void imageAuditCanBeDisabledWhileKeepingImageVerification() {
  var image=MediaAttachmentBO.builder().kind("IMAGE").verified(true).objectKey("article/retained/image").build();
  assertDoesNotThrow(()->checks.media(9,1,List.of(image)));
  var unverified=MediaAttachmentBO.builder().kind("IMAGE").verified(false).objectKey("article/retained/image").build();
  assertEquals(503,assertThrows(ContractProblemException.class,()->checks.media(9,1,List.of(unverified))).getStatus().value());
  verifyNoInteractions(reservations);

  org.springframework.test.util.ReflectionTestUtils.setField(checks,"wechatImageAuditEnabled",true);
  assertEquals(503,assertThrows(ContractProblemException.class,()->checks.media(9,1,List.of(image))).getStatus().value());
  verify(reservations).reserveAuditLogs(any());
 }
}
