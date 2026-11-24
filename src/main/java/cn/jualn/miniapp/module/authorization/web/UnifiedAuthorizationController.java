package cn.jualn.miniapp.module.authorization.web;

import cn.jualn.miniapp.module.authorization.support.UnifiedProblems;
import cn.jualn.miniapp.module.authorization.support.RbacProblems;
import cn.jualn.miniapp.module.authorization.service.UnifiedAuthorizationService;
import cn.jualn.miniapp.module.authorization.model.UnifiedModels;
import cn.jualn.miniapp.common.web.AdminQuery;
import cn.jualn.miniapp.module.admin.auth.service.AdminTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Set;

import static cn.jualn.miniapp.module.authorization.model.UnifiedModels.Intent;
import static cn.jualn.miniapp.module.authorization.model.UnifiedModels.Commit;
import static cn.jualn.miniapp.module.authorization.model.UnifiedModels.AuthorityRequest;
import static cn.jualn.miniapp.module.authorization.model.UnifiedModels.EffectiveDuties;
import static cn.jualn.miniapp.module.authorization.model.UnifiedModels.Options;
import static cn.jualn.miniapp.module.authorization.model.UnifiedModels.Preview;
import static cn.jualn.miniapp.module.authorization.model.UnifiedModels.Receipt;
import static cn.jualn.miniapp.module.authorization.model.UnifiedModels.Authority;
import static cn.jualn.miniapp.module.authorization.model.UnifiedModels.AuthorityPage;

@RestController
@RequiredArgsConstructor
@RequestMapping(value = "/v1/admin/authorization", produces = MediaType.APPLICATION_JSON_VALUE)
public class UnifiedAuthorizationController {
    private final AdminTokenService tokens;
    private final UnifiedAuthorizationService service;

    @GetMapping("/me/duty-summary")
    public ResponseEntity<UnifiedModels.DutySummary> dutySummary(HttpServletRequest request) {
        long actor = AdminQuery.read(this::actor);
        return ok(service.dutySummary(actor, AdminQuery.values(request, "dutyCode", 32)));
    }

    @GetMapping("/me/duty-options")
    public ResponseEntity<Options> options(@RequestParam(defaultValue = "20") int pageSize, @RequestParam(required = false) String cursor, HttpServletRequest request) {
        long actor = actor();
        query(request);
        return ok(service.options(actor, pageSize, cursor));
    }

    @GetMapping("/me/target-options")
    public ResponseEntity<UnifiedModels.TargetOptions> targets(@RequestParam String authorityId, @RequestParam String dutyCode,
                                                               @RequestParam(required = false) String targetType, @RequestParam(required = false) String q,
                                                               @RequestParam(defaultValue = "20") int pageSize, @RequestParam(required = false) String cursor, HttpServletRequest request) {
        long actor = actor();
        candidateQuery(request, Set.of("authorityId", "dutyCode", "targetType", "q", "pageSize", "cursor"));
        return ok(service.targetOptions(actor, authorityId, dutyCode, targetType, q, pageSize, cursor));
    }

    @GetMapping("/me/recipient-options")
    public ResponseEntity<UnifiedModels.RecipientOptions> recipients(@RequestParam String authorityId, @RequestParam String dutyCode,
                                                                     @RequestParam String targetType, @RequestParam(required = false) String personId, @RequestParam(required = false) String organizationId,
                                                                     @RequestParam(required = false) String organizationFilterId, @RequestParam(required = false) String q,
                                                                     @RequestParam(defaultValue = "20") int pageSize, @RequestParam(required = false) String cursor, HttpServletRequest request) {
        long actor = actor();
        candidateQuery(request, Set.of("authorityId", "dutyCode", "targetType", "personId", "organizationId", "organizationFilterId", "q", "pageSize", "cursor"));
        return ok(service.recipientOptions(actor, authorityId, dutyCode, new UnifiedModels.Target(targetType, personId, organizationId), organizationFilterId, q, pageSize, cursor));
    }

    private static void candidateQuery(HttpServletRequest request, Set<String> allowed) {
        request.getParameterMap().forEach((k, v) -> {
            if (!allowed.contains(k) || v.length != 1 || v[0].isBlank() || v[0].length() > ("cursor".equals(k) ? 2048 : "q".equals(k) ? 100 : 128))
                throw RbacProblems.invalidQuery(k);
        });
    }

    @GetMapping("/users/{userId}/effective-duties")
    public ResponseEntity<EffectiveDuties> effective(@PathVariable String userId, @RequestParam(defaultValue = "20") int pageSize, @RequestParam(required = false) String cursor, HttpServletRequest request) {
        long actor = actor();
        query(request);
        return ok(service.effective(actor, user(userId), pageSize, cursor));
    }

    @GetMapping("/delegation-authorities")
    public ResponseEntity<AuthorityPage> authorities(@RequestParam(defaultValue = "20") int pageSize, @RequestParam(required = false) String cursor, HttpServletRequest request) {
        long actor = actor();
        query(request);
        return ok(service.authorities(actor, pageSize, cursor));
    }

    @PostMapping("/delegation-authorities")
    public ResponseEntity<Authority> authority(@RequestBody AuthorityRequest request, @RequestHeader("Idempotency-Key") String key) {
        return ok(service.grantAuthority(actor(), request, key));
    }

    @PostMapping("/duty-previews")
    public ResponseEntity<Preview> preview(@RequestBody Intent intent, @RequestHeader("Idempotency-Key") String key) {
        return ok(service.preview(actor(), intent, key));
    }

    @GetMapping("/duty-previews/{previewId}")
    public ResponseEntity<Preview> preview(@PathVariable String previewId, @RequestParam(defaultValue = "20") int pageSize, @RequestParam(required = false) String cursor, HttpServletRequest request) {
        long actor = actor();
        query(request);
        return ok(service.preview(actor, previewId, pageSize, cursor));
    }

    @PostMapping("/duty-changes")
    public ResponseEntity<Receipt> commit(@RequestBody Commit request, @RequestHeader("Idempotency-Key") String key) {
        return ok(service.commit(actor(), request, key));
    }

    private long actor() {
        return RbacHttpAccess.authenticate(tokens);
    }

    private static long user(String value) {
        try {
            if (!value.matches("[1-9][0-9]*")) throw UnifiedProblems.missing();
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw UnifiedProblems.missing();
        }
    }

    private static void query(HttpServletRequest request) {
        request.getParameterMap().forEach((key, values) -> {
            if (!Set.of("pageSize", "cursor").contains(key) || values.length != 1 || values[0].isEmpty())
                throw RbacProblems.invalidQuery(key);
        });
    }

    private static <T> ResponseEntity<T> ok(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
