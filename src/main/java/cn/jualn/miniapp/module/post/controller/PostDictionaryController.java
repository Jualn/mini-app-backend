package cn.jualn.miniapp.module.post.controller;

import cn.jualn.miniapp.module.post.service.PostOrganizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PostDictionaryController {
    private final PostOrganizationService organization;

    @GetMapping("/v1/post-categories")
    public Object categories(@RequestParam(defaultValue = "ACTIVE") String status, @RequestParam(required = false) String postType,
                             @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int pageSize) {
        return organization.categories(status, postType, cursor, pageSize);
    }

    @GetMapping("/v1/topics")
    public Object topics(@RequestParam(defaultValue = "ACTIVE") String status, @RequestParam(required = false) String cursor,
                         @RequestParam(defaultValue = "20") int pageSize) {
        return organization.topics(status, cursor, pageSize);
    }
}
