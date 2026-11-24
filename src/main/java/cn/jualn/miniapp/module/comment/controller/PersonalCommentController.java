package cn.jualn.miniapp.module.comment.controller;
import cn.jualn.miniapp.module.comment.service.CommunityCommentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
@RestController @RequiredArgsConstructor
public class PersonalCommentController {
    private final CommunityCommentService comments;
    @GetMapping("/v1/users/me/post-comments") public Object mine(@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int pageSize,@RequestParam(defaultValue="NORMAL") String postType) {return response(comments.mine(cursor,pageSize,postType));}
    @GetMapping("/v1/users/me/post-comments/{commentId}") public Object mine(@PathVariable long commentId) {return response(comments.mine(commentId));}
    @GetMapping("/v1/users/me/post-comments/creation-receipt") public Object receipt(@RequestParam String key) {return response(comments.receipt(key));}
    @GetMapping("/v1/comment/{commentId}/context") public Object context(@PathVariable long commentId,@RequestParam long postId) {return response(comments.context(postId,commentId));}
    private Object response(Object value) {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).varyBy("Authorization").body(value);}
}
