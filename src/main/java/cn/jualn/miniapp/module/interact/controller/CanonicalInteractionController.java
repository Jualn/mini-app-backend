package cn.jualn.miniapp.module.interact.controller;
import cn.jualn.miniapp.module.interact.service.InteractionViews;
import cn.jualn.miniapp.module.interact.service.InteractionShares;
import cn.jualn.miniapp.module.interact.service.InteractionLikes;
import cn.jualn.miniapp.module.interact.service.InteractionLikeQueries;
import cn.jualn.miniapp.module.interact.service.InteractionCounts;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.article.converter.ArticleConverter;
import cn.jualn.miniapp.module.article.service.ArticleBodyCodec;
import cn.jualn.miniapp.module.article.service.ArticleService;
import cn.jualn.miniapp.module.article.vo.ArticleRepresentations.Statistics;
import cn.jualn.miniapp.module.comment.bo.ArticleCommentBO;
import cn.jualn.miniapp.module.comment.service.*;
import cn.jualn.miniapp.module.interact.dto.request.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
@Validated @RestController @RequiredArgsConstructor
@RequestMapping("/v1/interaction-targets/{targetType}/{targetId}")
public class CanonicalInteractionController {
    private final InteractionCounts interactionCounts;
    private final InteractionLikeQueries interactionLikeQueries;
    private final InteractionLikes interactionLikes;
    private final InteractionShares interactionShares;
    private final InteractionViews interactionViews;
 private final ArticleService articles;
 private final ArticleConverter converter;
 private final CanonicalCommentService comments;
 private final CommentTargetAccess targets;
 public record Like(boolean liked,long likeCount) {}
 public record Author(String userId,String nickname,@JsonInclude(JsonInclude.Include.ALWAYS) String avatarUrl,boolean isPlatformOperator) {}
 @JsonInclude(JsonInclude.Include.NON_NULL)
 public record Comment(String id,String targetType,String targetId,String content,Author author,String createdAt,
  String parentId,String replyToCommentId,long likeCount,boolean liked,long replyCount) {}
 @JsonInclude(JsonInclude.Include.NON_NULL)
 public record Comments(List<Comment> items,String nextCursor) {}
 @GetMapping("/like") public ResponseEntity<Like> like(@PathVariable String targetType,@PathVariable("targetId") String resourceId) {
  long targetId=ArticleConverter.lookupId(resourceId); var type=likeType(targetType,targetId); return response(new Like(interactionLikeQueries.isLiked(type,targetId),interactionCounts.getLikeCount(type,targetId)));
 }
 @PutMapping("/like") public ResponseEntity<Like> setLike(@PathVariable String targetType,@PathVariable("targetId") String resourceId) {
  long targetId=ArticleConverter.lookupId(resourceId); var type=likeType(targetType,targetId); interactionLikes.like(type,targetId); return response(new Like(true,interactionCounts.getLikeCount(type,targetId)));
 }
 @DeleteMapping("/like") public ResponseEntity<Like> removeLike(@PathVariable String targetType,@PathVariable("targetId") String resourceId) {
  long targetId=ArticleConverter.lookupId(resourceId); var type=likeType(targetType,targetId); interactionLikes.unlike(type,targetId); return response(new Like(false,interactionCounts.getLikeCount(type,targetId)));
 }
 @GetMapping("/comments") public ResponseEntity<Comments> list(@PathVariable String targetType,@PathVariable("targetId") String resourceId,
  @RequestParam(required=false) String parentId,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int pageSize) {
  long targetId=ArticleConverter.lookupId(resourceId); articleType(targetType); var page=comments.list(targetId,ArticleConverter.optionalLookupId(parentId),cursor,pageSize); return response(new Comments(page.items().stream().map(this::comment).toList(),page.nextCursor()));
 }
 @PostMapping("/comments") public ResponseEntity<Comment> create(@PathVariable String targetType,@PathVariable("targetId") String resourceId,
  @RequestBody CanonicalCommentRequest request) {
  long targetId=ArticleConverter.lookupId(resourceId); articleType(targetType); var value=comments.create(targetId,request.command());
  return ResponseEntity.created(URI.create("/v1/interaction-targets/ARTICLE/"+targetId+"/comments/"+value.id())).cacheControl(CacheControl.noStore()).body(comment(value));
 }
 @DeleteMapping("/comments/{commentId}") public ResponseEntity<Void> delete(@PathVariable String targetType,@PathVariable("targetId") String resourceId,
  @PathVariable("commentId") String commentResourceId) { long targetId=ArticleConverter.lookupId(resourceId); articleType(targetType); long commentId=ArticleConverter.lookupId(commentResourceId); comments.delete(targetId,commentId); return ResponseEntity.noContent().build(); }
 @PostMapping("/shares") public ResponseEntity<Statistics> share(@PathVariable String targetType,@PathVariable("targetId") String resourceId,
  @RequestBody CanonicalShareRequest request) {
  long targetId=ArticleConverter.lookupId(resourceId); articleType(targetType); interactionShares.share(TargetType.ARTICLE,targetId,"WECHAT_FRIEND".equals(request.platform())?1:2); return response(converter.statistics(articles.statistics(targetId)));
 }
 @PostMapping("/views") public ResponseEntity<Statistics> view(@PathVariable String targetType,@PathVariable("targetId") String resourceId) {
  long targetId=ArticleConverter.lookupId(resourceId); articleType(targetType); interactionViews.view(TargetType.ARTICLE,targetId); return response(converter.statistics(articles.statistics(targetId)));
 }
 private TargetType likeType(String type,long id) {
  if("ARTICLE".equals(type)) { articles.assertPublic(id); return TargetType.ARTICLE; }
  if("COMMENT".equals(type)) { targets.requireArticle(id,false); return TargetType.COMMENT; }
  ArticleBodyCodec.invalid("#/targetType","Invalid interaction target"); throw new IllegalStateException();
 }
 private void articleType(String type) { if(!"ARTICLE".equals(type)) ArticleBodyCodec.invalid("#/targetType","Article target required"); }
 private Comment comment(ArticleCommentBO c) {
  var a=c.author(); return new Comment(c.id().toString(),"ARTICLE",c.articleId().toString(),c.content(),
   new Author(a.getId().toString(),a.getNickname(),a.getAvatarUrl(),a.isPlatformOperator()),ArticleConverter.time(c.createdAt()),
   ArticleConverter.id(c.parentId()),ArticleConverter.id(c.replyToCommentId()),c.likeCount(),c.liked(),c.replyCount());
 }
 private <T> ResponseEntity<T> response(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
}
