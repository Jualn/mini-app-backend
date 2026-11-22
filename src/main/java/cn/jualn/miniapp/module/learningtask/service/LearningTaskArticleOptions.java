package cn.jualn.miniapp.module.learningtask.service;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Action;
import cn.jualn.miniapp.module.learningtask.mapper.LearningTaskMapper;
import cn.jualn.miniapp.module.article.service.ArticleReferenceReadService;
import cn.jualn.miniapp.module.authorization.model.UnifiedModels.Target;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.Dependency;
import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.conflict;
import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.invalid;
import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.missing;
import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.unavailable;
@Service @RequiredArgsConstructor
public class LearningTaskArticleOptions {
 private final LearningTaskAuthorizationPolicy authorization;
 private final LearningTaskScopePolicy scopes;
 private final LearningTaskMapper tasks;
 private final ArticleReferenceReadService articles;
 @Transactional(isolation=Isolation.REPEATABLE_READ)
 public ArticleReferenceReadService.Options list(Long task,String type,String org,String q,String cursor,int size) {
  return list(task,type,org,q,cursor,size,null,"-publishedAt");
 }
 @Transactional(isolation=Isolation.REPEATABLE_READ)
 public ArticleReferenceReadService.Options list(Long task,String type,String org,String q,String cursor,int size,Long categoryId,String sort) {
  try {
  long actor=authorization.authorize(Action.READ,task,null);
  authorization.authorize(Action.EDIT,task,null);
  if(task!=null) {
   if(type!=null || org!=null) throw invalid("targetType");
   var current=tasks.selectById(task);if(current==null) throw missing();
   if(!"DRAFT".equals(current.getStatus())) throw conflict("state-conflict");
  } else {
   if(type==null || !("ORGANIZATION".equals(type) && org!=null && !org.isBlank() && org.length()<=128 || "PLATFORM".equals(type) && org==null)) throw invalid("managementTarget");
   scopes.creation(actor,new Target(type,null,org));
  }
  var result=articles.options(q,cursor,size,scopes.articleOptionsVersion(actor,task,type,org),categoryId,sort);
  scopes.finish(actor,false);return result;
  } catch(cn.jualn.miniapp.common.exception.ContractProblemException e) {
   if(e.getStatus().is5xxServerError() && !"/problems/learning-task-dependency-unavailable".equals(e.getType())) {var problem=unavailable(Dependency.AUTHORIZATION);problem.initCause(e);throw problem;}
   throw e;
  } catch(org.springframework.dao.DataAccessException e) {
   var problem=unavailable(Dependency.AUTHORIZATION);problem.initCause(e);throw problem;
  }
 }
}
