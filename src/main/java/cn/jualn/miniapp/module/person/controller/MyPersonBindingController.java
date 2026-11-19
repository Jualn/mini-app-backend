package cn.jualn.miniapp.module.person.controller;

import cn.dev33.satoken.stp.StpUtil;
import cn.jualn.miniapp.module.person.service.binding.ManagedPersonBindingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class MyPersonBindingController {
    private final ManagedPersonBindingService service;
    @GetMapping("/v1/users/me/person-binding") public ResponseEntity<?> mine() {
        return ManagedPersonController.resource(service.getMyPersonBinding(StpUtil.getLoginIdAsLong()), cn.jualn.miniapp.module.person.converter.ManagedPersonConverter::mine);
    }
}
