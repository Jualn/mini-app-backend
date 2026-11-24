package cn.jualn.miniapp.module.learningtask.service;
import cn.jualn.miniapp.module.learningtask.mapper.LearningTaskMapper;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
/** All retained references, including draft/closed/cancelled tasks. No actor-specific visibility filter. */
@Service @RequiredArgsConstructor
public class LearningTaskArticleUsage {
 private final LearningTaskMapper tasks;
 public boolean hasActiveReferences(long article) {
  try {return !tasks.activeArticleReferences(article).isEmpty();}
  catch(org.springframework.dao.DataAccessException e) {var failure=new ContractProblemException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"/problems/service-unavailable","LearningTask reference evidence is unavailable");failure.initCause(e);throw failure;}
 }
 public boolean hasReferences(long article) {
  try {return !tasks.retainedArticleReferences(article).isEmpty();}
  catch(org.springframework.dao.DataAccessException e) {
   var failure=new ContractProblemException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"/problems/service-unavailable","LearningTask reference evidence is unavailable"); failure.initCause(e);throw failure;
  }
 }
}
