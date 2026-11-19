package cn.jualn.miniapp.module.article.service;
import cn.jualn.miniapp.module.article.entity.ArticlePublisher;
import cn.jualn.miniapp.module.article.mapper.ArticlePublisherMapper;
import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
/** Optional explicit deployment bootstrap. Never manufactures an administrator for an initial audit field. */
@RequiredArgsConstructor
public class ArticlePublisherBootstrap implements ApplicationRunner {
 private final ArticlePublisherMapper publishers;
 private final CurrentAuthorization authorization;
 private final ArticleSafetyChecks safety;
 @Value("${app.article.initial-publisher-name:}") private String name="";
 @Value("${app.article.bootstrap-admin-id:0}") private long adminId;
 @Override public void run(ApplicationArguments arguments) {
  if(publishers.selectById(1L)!=null) return;
  if(ArticleBodyCodec.blank(name) && adminId==0) return;
  if(ArticleBodyCodec.blank(name) || ArticleBodyCodec.length(name)>120 || adminId<=0) throw new IllegalStateException("Article publisher bootstrap requires a real authorized admin account and display name");
  // Preserve explicit LEGACY initialization; an old role cannot bypass the independent Article gate after source switching.
  authorization.requireLegacyOnly(adminId,AdminPermissionPolicy.CONTENT_MANAGE);
  safety.text(adminId,ArticleBodyCodec.trim(name));
  ArticlePublisher publisher=new ArticlePublisher(); publisher.setId(1L); publisher.setDisplayName(ArticleBodyCodec.trim(name)); publisher.setRevision(1L); publisher.setUpdatedBy(adminId); publisher.setUpdatedAt(ArticleWriter.now());
  try { if(publishers.insert(publisher)!=1) throw new IllegalStateException("Article publisher bootstrap failed"); }
  catch(DuplicateKeyException concurrentBootstrap) { if(publishers.selectById(1L)==null) throw concurrentBootstrap; }
 }
}
