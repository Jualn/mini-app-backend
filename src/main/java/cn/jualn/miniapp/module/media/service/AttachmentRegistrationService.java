package cn.jualn.miniapp.module.media.service;

import cn.jualn.miniapp.common.enums.*;
import cn.jualn.miniapp.module.media.enums.MediaType;
import cn.jualn.miniapp.common.exception.*;
import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import cn.jualn.miniapp.module.media.entity.*;
import cn.jualn.miniapp.module.media.mapper.*;
import cn.jualn.miniapp.module.media.converter.MediaConverter;
import cn.jualn.miniapp.third.cos.service.CosService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.http.HttpStatus;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** Durable request identity, remote preparation outside locks, then short atomic registration. */
@Service @lombok.extern.slf4j.Slf4j
public class AttachmentRegistrationService {
 private final AttachmentRegistrationMapper intents;
 private final MediaAttachmentMapper attachments;
 private final MediaUploadRecordMapper uploads;
 private final MediaUploadRecordService lifecycle;
 private final ArticleMediaService article;
 private final ManagedAttachmentInspector inspector;
 private final CurrentAuthorization authorization;
 private final CosService cos;
 private final MediaConverter converter;
 private final ObjectMapper json;
 private final TransactionTemplate tx;
 public AttachmentRegistrationService(AttachmentRegistrationMapper intents,MediaAttachmentMapper attachments,
   MediaUploadRecordMapper uploads,MediaUploadRecordService lifecycle,ArticleMediaService article,
   ManagedAttachmentInspector inspector,CurrentAuthorization authorization,CosService cos,MediaConverter converter,
   ObjectMapper json,PlatformTransactionManager manager) {
  this.intents=intents;this.attachments=attachments;this.uploads=uploads;this.lifecycle=lifecycle;this.article=article;
  this.inspector=inspector;this.authorization=authorization;this.cos=cos;this.converter=converter;this.json=json;
  tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.setTimeout(20);
 }
 public MediaAttachmentBO register(long actor,String key,String kind,String name,String url) {
  if(key==null || key.isBlank() || key.length()>128) throw ContractProblemException.validation(
   new ContractProblemException.Violation("header","Idempotency-Key","INVALID_VALUE","Use a stable key of 1..128 characters"));
  String fingerprint=fingerprint(kind,name,url);
  try {
   var replay=tx.execute(s->{intents.reserve(actor,key,fingerprint);return replay(actor,key,fingerprint,kind,url);});
   if(replay!=null) return replay;
   Supplier<MediaAttachmentBO> prepared=article.handles(kind,url)?article.prepareRegistration(kind,name,url,actor):prepare(actor,kind,name,url);
   return tx.execute(s->{
    var existing=replay(actor,key,fingerprint,kind,url); if(existing!=null) return existing;
    var value=prepared.get();
    if(intents.complete(actor,key,value.getId())!=1) throw new IllegalStateException("Registration identity changed");
    return value;
   });
  } catch(org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException | ExternalServiceException failure) {
   throw unavailable(failure);
  }
 }
 private MediaAttachmentBO replay(long actor,String key,String fingerprint,String kind,String url) {
  var intent=intents.lock(actor,key);
  if(!fingerprint.equals(intent.fingerprint())) throw ContractProblemException.conflict("idempotency-key-reused","This key already identifies a different request");
  authorize(actor,kind,url);
  if(intent.attachmentId()==null) return null;
  var value=attachments.lock(intent.attachmentId());
  if(value==null) throw gone("attachment-registration-expired");
  requireReplayable(value);
  return converter.toBO(value);
 }
 private void authorize(long actor,String kind,String url) {
  if(article.handles(kind,url)) { article.authorizeWrite();return; }
  String source=cos.resolveManagedObjectKey(url);
  if(source==null) {authorization.requireLegacyOnly(actor,"content:manage");return;}
  var upload=uploads.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MediaUploadRecord>().eq(MediaUploadRecord::getObjectKey,source));
  if(upload==null || !source.equals(upload.getObjectKey()) || !Long.valueOf(actor).equals(upload.getUserId())) throw new ContractProblemException(HttpStatus.FORBIDDEN,"/problems/forbidden","Use an upload owned by this administrator");
  authorization.require(actor,"content:manage",CurrentAuthorization.namespace(TargetType.fromCode(upload.getTargetType())),"media:write");
 }
 private Supplier<MediaAttachmentBO> prepare(long actor,String kind,String name,String url) {
  if(!Set.of("IMAGE","POSTER","QR_CODE","PDF","WORD","LINK").contains(kind) || name==null || name.isBlank() || name.trim().length()>200 || url==null || !url.matches("^https?://.+")) throw ContractProblemException.validation(new ContractProblemException.Violation("body","#","INVALID_VALUE","Invalid attachment"));
  authorize(actor,kind,url);
  String source=cos.resolveManagedObjectKey(url);
  if(source==null) return () -> insert(actor,kind,name.trim(),url.trim(),null,null,now().plusHours(3));
  var upload=uploads.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MediaUploadRecord>().eq(MediaUploadRecord::getObjectKey,source));
  if(upload!=null && upload.getBoundAttachmentId()!=null) return () -> duplicate(upload.getBoundAttachmentId(),kind,name.trim());
  // Eligibility checked again under the upload lock at commit.
  if(upload==null || upload.getStatus()!=0 || upload.getCleanupStartedAt()!=null || !upload.getCleanupAfter().isAfter(now())) throw gone("attachment-candidate-expired");
  String snapshot=MediaObjectKeys.retainedPrefix(TargetType.fromCode(upload.getTargetType()),actor)+UUID.randomUUID();
  lifecycle.recordPending(actor,TargetType.fromCode(upload.getTargetType()),List.of(snapshot),upload.getCleanupAfter());
  cos.copyObject(source,snapshot);
  var metadata=inspector.inspect(snapshot,kind);
  return ()->{
   var locked=lifecycle.lockAttachmentUpload(actor,source);
   authorize(actor,kind,url);
   if(locked.getBoundAttachmentId()!=null) return duplicate(locked.getBoundAttachmentId(),kind,name.trim());
   var copy=uploads.selectForUpdate(snapshot);
   if(copy==null || copy.getStatus()!=0 || copy.getCleanupStartedAt()!=null || !copy.getCleanupAfter().isAfter(now())) throw gone("attachment-candidate-expired");
   var value=insert(actor,kind,name.trim(),cos.buildPublicUrl(snapshot),snapshot,metadata,locked.getCleanupAfter());
   lifecycle.bindRegisteredAttachment(copy,value.getId());
   locked.setBoundAttachmentId(value.getId());locked.setStatus(0);
   if(uploads.updateById(locked)!=1) throw new IllegalStateException("Source registration failed");
   return value;
  };
 }
 private MediaAttachmentBO duplicate(long id,String kind,String name) {
  var value=attachments.lock(id); requireSame(value,kind,name);requireReplayable(value);return converter.toBO(value);
 }
 private MediaAttachmentBO insert(long actor,String kind,String name,String url,String objectKey,ManagedAttachmentInspector.Metadata metadata,LocalDateTime expires) {
  var type=switch(kind) {case "IMAGE","POSTER","QR_CODE"->MediaType.IMAGE;case "PDF"->MediaType.PDF;case "WORD"->MediaType.WORD;default->MediaType.URL;};
  var entity=MediaAttachment.builder().kind(kind).type(type.getCode()).registered(true).registeredBy(actor)
   .objectKey(objectKey).url(url).originalName(name).sortOrder(0).retention("CANDIDATE").expiresAt(expires).deleted(false).verified(true).build();
  if(metadata!=null) {entity.setContentType(metadata.contentType());entity.setSizeBytes(metadata.sizeBytes());}
  if(attachments.insert(entity)!=1) throw new IllegalStateException("Attachment insert failed");
  return converter.toBO(entity);
 }
 static void requireSame(MediaAttachment value,String kind,String name) {
  if(value==null || !kind.equals(value.getKind()) || !name.equals(value.getOriginalName())) throw ContractProblemException.conflict("attachment-registration-conflict","Object already has different attachment metadata");
 }
 static void requireReplayable(MediaAttachment value) {
  if(Boolean.TRUE.equals(value.getDeleted())) throw gone("attachment-deleted");
  if("CANDIDATE".equals(value.getRetention()) && !value.getExpiresAt().isAfter(now())) throw gone("attachment-candidate-expired");
 }
 private String fingerprint(String kind,String name,String url) {
  try {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(List.of(kind,name,url))));}
  catch(java.io.IOException | java.security.NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
 }
 static LocalDateTime now() {return LocalDateTime.now(ZoneId.of("Asia/Shanghai")).truncatedTo(java.time.temporal.ChronoUnit.MICROS);}
 static ContractProblemException gone(String code) {return new ContractProblemException(HttpStatus.GONE,"/problems/"+code,"Attachment registration is no longer available");}
 static ContractProblemException unavailable(Throwable cause) {
  String category=cause instanceof org.springframework.dao.DataAccessException || cause instanceof org.springframework.transaction.TransactionException?"database":cause instanceof ExternalServiceException?"remote":"internal";
  log.warn("result=failure errorCategory={} exceptionType={}",category,cause.getClass().getSimpleName());
  var problem=unavailable();problem.initCause(cause);return problem;
 }
 static ContractProblemException unavailable() {return new ContractProblemException(HttpStatus.SERVICE_UNAVAILABLE,"/problems/service-unavailable","Media dependency is unavailable; retry the same registration intent");}
}
