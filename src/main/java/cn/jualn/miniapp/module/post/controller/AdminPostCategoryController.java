package cn.jualn.miniapp.module.post.controller;
import cn.jualn.miniapp.module.post.service.PostCategoryMaintenance;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
@RestController @RequiredArgsConstructor
public class AdminPostCategoryController {
    private final PostCategoryMaintenance categories;
    public record Category(Long categoryId) { }
    @PutMapping("/v1/admin/contents/post/{postId}/category")
    public Object set(@PathVariable long postId,@RequestHeader(value="If-Match",required=false) String tag,@RequestBody Category body) {
        var result=categories.set(postId,body.categoryId(),tag);
        return ResponseEntity.ok().eTag(result.etag()).cacheControl(CacheControl.noStore()).varyBy("Authorization").body(result.value());
    }
}
