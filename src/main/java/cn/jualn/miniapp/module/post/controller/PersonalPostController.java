package cn.jualn.miniapp.module.post.controller;

import cn.jualn.miniapp.module.post.service.PostCommunityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;

@RestController @RequiredArgsConstructor
public class PersonalPostController {
    private final PostCommunityService community;
    @GetMapping("/v1/users/me/posts")
    public Object mine(@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int pageSize,@RequestParam(defaultValue="NORMAL") String postType) {return noStore(community.mine(cursor,pageSize,postType));}
    @GetMapping("/v1/users/me/posts/{postId}")
    public Object mine(@PathVariable long postId) {return noStore(community.mine(postId));}
    @GetMapping("/v1/users/me/posts/creation-receipt")
    public Object receipt(@RequestParam String key) {return noStore(community.receipt(key));}
    @GetMapping("/v1/post/{postId}/favorite")
    public Object favorite(@PathVariable long postId) {return noStore(community.favorite(postId,null));}
    @PutMapping("/v1/post/{postId}/favorite")
    public Object set(@PathVariable long postId) {return noStore(community.favorite(postId,true));}
    @DeleteMapping("/v1/post/{postId}/favorite")
    public Object remove(@PathVariable long postId) {return noStore(community.favorite(postId,false));}
    @GetMapping("/v1/users/me/post-favorites")
    public Object favorites(@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int pageSize,@RequestParam(defaultValue="NORMAL") String postType) {return noStore(community.saved(false,cursor,pageSize,postType));}
    @GetMapping("/v1/users/me/post-view-history")
    public Object history(@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int pageSize,@RequestParam(defaultValue="NORMAL") String postType) {return noStore(community.saved(true,cursor,pageSize,postType));}
    @DeleteMapping("/v1/users/me/post-view-history")
    public Object clear() {return noStore(new cn.jualn.miniapp.module.post.bo.PostCommunityBO.HistoryCleared(community.clearHistory()));}
    private Object noStore(Object value) {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).varyBy("Authorization").body(value);}
}
