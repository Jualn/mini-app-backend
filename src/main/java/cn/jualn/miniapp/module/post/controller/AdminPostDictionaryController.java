package cn.jualn.miniapp.module.post.controller;

import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import cn.jualn.miniapp.module.post.bo.PostCategoryBO;
import cn.jualn.miniapp.module.post.bo.PostTopicBO;
import cn.jualn.miniapp.module.post.dto.request.PostDictionaryRequest;
import cn.jualn.miniapp.module.post.bo.PostVersionedBO;
import cn.jualn.miniapp.module.post.service.PostDictionaryAuthorization;
import cn.jualn.miniapp.module.post.service.PostOrganizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/v1/admin")
@RequiredArgsConstructor
public class AdminPostDictionaryController {
    private final PostOrganizationService organization;
    private final PostDictionaryAuthorization authorization;

    @GetMapping("/post-categories")
    public Object categories(@RequestParam(defaultValue = "ALL") String status, @RequestParam(required = false) String postType,
                             @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int pageSize) {
        read();
        return organization.categories(status, postType, cursor, pageSize);
    }

    @GetMapping("/topics")
    public Object topics(@RequestParam(defaultValue = "ALL") String status, @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int pageSize) {
        read();
        return organization.topics(status, cursor, pageSize);
    }

    @GetMapping("/post-categories/{id}")
    public ResponseEntity<?> category(@PathVariable long id) {
        read();
        return response(organization.get(true, id), null);
    }

    @GetMapping("/topics/{id}")
    public ResponseEntity<?> topic(@PathVariable long id) {
        read();
        return response(organization.get(false, id), null);
    }

    @PostMapping("/post-categories")
    public ResponseEntity<?> createCategory(@RequestBody PostDictionaryRequest body) {
        long actor = write();
        var result = organization.create(true, body.command(), actor);
        return response(result, "/v1/admin/post-categories/" + ((PostCategoryBO) result.value()).id());
    }

    @PostMapping("/topics")
    public ResponseEntity<?> createTopic(@RequestBody PostDictionaryRequest body) {
        long actor = write();
        var result = organization.create(false, body.command(), actor);
        return response(result, "/v1/admin/topics/" + ((PostTopicBO) result.value()).id());
    }

    @PatchMapping("/post-categories/{id}")
    public ResponseEntity<?> editCategory(@PathVariable long id, @RequestBody PostDictionaryRequest body, @RequestHeader(value = "If-Match", required = false) String match) {
        return response(organization.edit(true, id, body.command(), match, write()), null);
    }

    @PatchMapping("/topics/{id}")
    public ResponseEntity<?> editTopic(@PathVariable long id, @RequestBody PostDictionaryRequest body, @RequestHeader(value = "If-Match", required = false) String match) {
        return response(organization.edit(false, id, body.command(), match, write()), null);
    }

    private void read() {
        authorization.read();
    }

    private long write() {
        long actor = AdminStpUtil.STP_LOGIC.getLoginIdAsLong();
        authorization.write(actor);
        return actor;
    }

    private ResponseEntity<?> response(PostVersionedBO<?> result, String location) {
        var builder = location == null ? ResponseEntity.ok() : ResponseEntity.created(URI.create(location));
        return builder.eTag(result.etag()).cacheControl(CacheControl.noStore()).varyBy("Authorization").body(result.value());
    }
}
