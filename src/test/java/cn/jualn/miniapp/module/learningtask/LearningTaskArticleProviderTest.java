package cn.jualn.miniapp.module.learningtask;
import cn.jualn.miniapp.module.learningtask.service.*;
import cn.jualn.miniapp.module.article.service.ArticleReferenceReadService;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class LearningTaskArticleProviderTest {
 final ArticleReferenceReadService articles=mock(ArticleReferenceReadService.class);
 final UnavailableLearningTaskProviders provider=new UnavailableLearningTaskProviders(articles,mock(LearningTaskAuthorizationPolicy.class),mock(LearningTaskTextSafety.class),mock(cn.jualn.miniapp.module.user.service.UserLearningTaskRecipientService.class),mock(LearningTaskNotifications.class));
 @Test void publicMaterialUsesItsOwnerWithoutArticleManagementAuthority() {
  assertDoesNotThrow(()->provider.article(9,17));verify(articles).requirePublicReference(17);
  when(articles.publiclyVisibleIds(Set.of(17L))).thenReturn(Set.of(17L));assertEquals(Set.of(17L),provider.publiclyVisibleArticles(Set.of(17L)));
 }
 @Test void unavailableMaterialAndUnknownEvidenceHaveDifferentOutcomes() {
  doThrow(ContractProblemException.conflict("resource-unavailable","Withdrawn")).when(articles).requirePublicReference(17);
  assertEquals("/problems/learning-task-resource-unavailable",assertThrows(ContractProblemException.class,()->provider.article(9,17)).getType());
  doThrow(cn.jualn.miniapp.module.article.service.ArticleOrganizationPolicy.unavailable()).when(articles).requirePublicReference(17);
  var unknown=assertThrows(ContractProblemException.class,()->provider.article(9,17));assertEquals(503,unknown.getStatus().value());assertEquals("/problems/learning-task-dependency-unavailable",unknown.getType());
  when(articles.publiclyVisibleIds(Set.of(17L))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic dependency failure"));
  assertEquals(503,assertThrows(ContractProblemException.class,()->provider.publiclyVisibleArticles(Set.of(17L))).getStatus().value());
 }
}
