package cn.jualn.miniapp.module.person.controller;

import cn.jualn.miniapp.module.person.service.access.PersonAuthentication;
import cn.jualn.miniapp.module.person.service.roster.PartyPersonnelDirectory;
import cn.jualn.miniapp.module.person.service.roster.PartyPersonnelDirectory.OrganizationPage;
import cn.jualn.miniapp.module.person.service.roster.PartyRosterService;
import cn.jualn.miniapp.module.person.service.roster.PartyPersonnelPlans;
import cn.jualn.miniapp.module.person.converter.QualificationConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PartyPersonnelController {
    private final PersonAuthentication authentication;
    private final PartyPersonnelDirectory directory;
    private final PartyRosterService roster;

    private static final int MAX_WORKFLOW_BODY_BYTES = 16_000_000;
    private final PartyPersonnelPlans plans;

    @PostMapping("/v1/admin/party-personnel/organizations/{organizationId}/matches")
    public ResponseEntity<PartyPersonnelPlans.Match> match(@PathVariable String organizationId, @RequestBody byte[] body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(plans.match(authentication.actor(), organizationId,
                QualificationController.body(body, PartyPersonnelPlans.MatchRequest.class)));
    }

    @PostMapping("/v1/admin/party-personnel/organizations/{organizationId}/plans")
    public ResponseEntity<PartyPersonnelPlans.Plan> preview(@PathVariable String organizationId, @RequestBody byte[] body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(plans.preview(authentication.actor(), organizationId,
                QualificationController.body(body, PartyPersonnelPlans.Request.class, MAX_WORKFLOW_BODY_BYTES)));
    }

    @PostMapping("/v1/admin/party-personnel/organizations/{organizationId}/plans/{planId}:commit")
    public ResponseEntity<PartyPersonnelPlans.Receipt> commit(@PathVariable String organizationId, @PathVariable String planId,
            @RequestBody byte[] body, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(plans.commit(authentication.actor(), organizationId, planId,
                QualificationController.body(body, PartyPersonnelPlans.Confirm.class, MAX_WORKFLOW_BODY_BYTES), key));
    }

    @GetMapping("/v1/admin/party-personnel/organizations")
    public ResponseEntity<OrganizationPage> organizations(
            @RequestParam(defaultValue = "1") String page,
            @RequestParam(defaultValue = "20") String pageSize,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String q) {
        long actor = authentication.actor();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(directory.organizations(actor, PersonController.page(page), PersonController.size(pageSize), type, q));
    }

    @GetMapping("/v1/admin/party-personnel/organizations/{organizationId}/members")
    public ResponseEntity<PartyPersonnelDirectory.MemberPage> members(
            @PathVariable String organizationId,
            @RequestParam(defaultValue = "1") String page,
            @RequestParam(defaultValue = "20") String pageSize,
            @RequestParam(required = false) String q) {
        long actor = authentication.actor();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(directory.members(actor, organizationId, PersonController.page(page), PersonController.size(pageSize), q));
    }

    @GetMapping("/v1/admin/party-personnel/organizations/{organizationId}/members/{personId}")
    public ResponseEntity<PartyPersonnelDirectory.Member> member(@PathVariable String organizationId,
                                                                  @PathVariable String personId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(directory.member(authentication.actor(), organizationId, personId));
    }

    @GetMapping("/v1/admin/party-personnel/organizations/{organizationId}/imports")
    public ResponseEntity<?> imports(@PathVariable String organizationId,
            @RequestParam(defaultValue = "1") String page, @RequestParam(defaultValue = "20") String pageSize) {
        var results = roster.organizationImports(authentication.actor(), organizationId,
                PersonController.page(page), PersonController.size(pageSize));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(QualificationConverter.page(results, java.util.function.Function.identity()));
    }
}
