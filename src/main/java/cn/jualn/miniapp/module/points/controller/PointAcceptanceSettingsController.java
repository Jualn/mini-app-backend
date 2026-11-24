package cn.jualn.miniapp.module.points.controller;

import cn.jualn.miniapp.module.person.service.access.PersonAuthentication;
import cn.jualn.miniapp.module.points.bo.PointAcceptanceSettings.Decision;
import cn.jualn.miniapp.module.points.bo.PointAcceptanceSettings.Page;
import cn.jualn.miniapp.module.points.bo.PointAcceptanceSettings.Setting;
import cn.jualn.miniapp.module.points.service.PointAcceptanceSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/admin/points/accepting-organizations")
public class PointAcceptanceSettingsController {
    private final PointAcceptanceSettingsService settings;
    private final PersonAuthentication authentication;
    @GetMapping
    public ResponseEntity<Page> list(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int pageSize) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(settings.list(authentication.actor(), page, pageSize));
    }
    @GetMapping("/{organizationId}")
    public ResponseEntity<Setting> get(@PathVariable String organizationId) {
        return resource(settings.get(authentication.actor(), organizationId));
    }
    @GetMapping("/{organizationId}/decisions")
    public ResponseEntity<Page> history(@PathVariable String organizationId, @RequestParam(defaultValue="1") int page,
                                        @RequestParam(defaultValue="20") int pageSize) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(settings.history(authentication.actor(), organizationId, page, pageSize));
    }
    @PostMapping("/{organizationId}/decisions")
    public ResponseEntity<Setting> decide(@PathVariable String organizationId, @RequestBody Decision body,
            @RequestHeader(value="Idempotency-Key",required=false) String key, @RequestHeader(value="If-Match",required=false) String match) {
        return resource(settings.decide(authentication.actor(), organizationId, body, key, match));
    }
    private static ResponseEntity<Setting> resource(Setting result) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag("\""+result.version()+"\"").body(result);
    }
}
