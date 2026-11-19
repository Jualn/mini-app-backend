package cn.jualn.miniapp.module.person.controller;

import cn.jualn.miniapp.common.web.AdminNamesVO;
import cn.jualn.miniapp.common.web.AdminQuery;
import cn.jualn.miniapp.module.person.bo.PersonBO;
import cn.jualn.miniapp.module.person.dto.PersonRequests;
import cn.jualn.miniapp.module.person.converter.PersonConverter;
import cn.jualn.miniapp.module.person.service.access.PersonAuthentication;
import cn.jualn.miniapp.module.person.service.facts.PersonCatalog;
import cn.jualn.miniapp.module.person.service.facts.PersonFoundationService;

import java.util.function.Function;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static cn.jualn.miniapp.module.person.PersonProblems.invalid;

@RestController
@RequiredArgsConstructor
public class PersonController {
    private final PersonFoundationService service;
    private final PersonAuthentication authentication;

    @ModelAttribute
    public void authenticate() {
        authentication.actor();
    }

    @GetMapping("/v1/admin/organizations/{organizationId}/roster-completeness")
    public ResponseEntity<?> rosterCompleteness(@PathVariable String organizationId) {
        return resource(service.rosterCompleteness(authentication.actor(), organizationId), Function.identity());
    }

    @PostMapping("/v1/admin/organizations/{organizationId}/roster-completeness/changes")
    public ResponseEntity<?> changeRosterCompleteness(@PathVariable String organizationId, @RequestBody PersonRequests.Roster body,
                                                      @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestHeader(value = "If-Match", required = false) String match) {
        return resource(service.changeRosterCompleteness(authentication.actor(), organizationId, body.command(), key, match), Function.identity());
    }

    @GetMapping("/v1/admin/organizations/{organizationId}/roster-completeness/history")
    public ResponseEntity<?> rosterCompletenessHistory(@PathVariable String organizationId, @RequestParam(defaultValue = "1") String page, @RequestParam(defaultValue = "20") String pageSize) {
        return ok(PersonConverter.page(service.rosterCompletenessHistory(authentication.actor(), organizationId, page(page), size(pageSize)), Function.identity()));
    }

    @GetMapping("/v1/admin/identity-stages")
    public ResponseEntity<PersonCatalog.Catalog> stages() {
        return ok(service.stages(authentication.actor()));
    }

    @GetMapping("/v1/admin/persons")
    public ResponseEntity<?> persons(@RequestParam(defaultValue = "1") String page, @RequestParam(defaultValue = "20") String pageSize, @RequestParam(required = false) String organizationId, @RequestParam(required = false) String q, @RequestParam(required = false) String status) {
        return ok(PersonConverter.page(service.persons(authentication.actor(), page(page), size(pageSize), organizationId, q, status), PersonConverter::summary));
    }

    @GetMapping("/v1/admin/persons/{personId}/identity")
    public ResponseEntity<?> identity(@PathVariable String personId) {
        return resource(service.identity(authentication.actor(), personId), PersonConverter::identity);
    }

    @PostMapping("/v1/admin/persons/{personId}/identity-changes")
    public ResponseEntity<?> changeIdentity(@PathVariable String personId, @RequestBody PersonRequests.Identity body, @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestHeader(value = "If-Match", required = false) String match) {
        return resource(service.changeIdentity(authentication.actor(), personId, body.command(), key, match), PersonConverter::identity);
    }

    @GetMapping("/v1/admin/persons/{personId}/identity-history")
    public ResponseEntity<?> identityHistory(@PathVariable String personId, @RequestParam(defaultValue = "1") String page, @RequestParam(defaultValue = "20") String pageSize) {
        return ok(PersonConverter.page(service.identityHistory(authentication.actor(), personId, page(page), size(pageSize)), h -> PersonConverter.history(h, PersonConverter::identity)));
    }

    @GetMapping("/v1/admin/persons/{personId}/memberships")
    public ResponseEntity<?> memberships(@PathVariable String personId) {
        return resource(service.memberships(authentication.actor(), personId), PersonConverter::memberships);
    }

    @PutMapping("/v1/admin/persons/{personId}/memberships")
    public ResponseEntity<?> replace(@PathVariable String personId, @RequestBody PersonRequests.Memberships body, @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestHeader(value = "If-Match", required = false) String match) {
        return resource(service.replaceMemberships(authentication.actor(), personId, body.command(), key, match), PersonConverter::memberships);
    }

    @GetMapping("/v1/admin/persons/{personId}/membership-history")
    public ResponseEntity<?> membershipHistory(@PathVariable String personId, @RequestParam(defaultValue = "1") String page, @RequestParam(defaultValue = "20") String pageSize) {
        return ok(PersonConverter.page(service.membershipHistory(authentication.actor(), personId, page(page), size(pageSize)), h -> PersonConverter.history(h, PersonConverter::memberships)));
    }

    @GetMapping("/v1/admin/organizations")
    public ResponseEntity<?> organizations(@RequestParam(defaultValue = "1") String page, @RequestParam(defaultValue = "20") String pageSize, @RequestParam(required = false) String status) {
        return ok(PersonConverter.page(service.organizations(authentication.actor(), page(page), size(pageSize), status), PersonConverter::organization));
    }

    @GetMapping("/v1/admin/organizations/names")
    public ResponseEntity<AdminNamesVO.Organizations> names(jakarta.servlet.http.HttpServletRequest request) {
        var ids = AdminQuery.values(request, "organizationId", 100);
        var at = java.time.Instant.now();
        var names = AdminQuery.read(() -> service.organizationNames(authentication.actor(), ids));
        return ok(new AdminNamesVO.Organizations(at,
                names.stream().map(AdminNamesVO::organization).toList()));
    }

    @GetMapping("/v1/admin/organizations/{organizationId}/members")
    public ResponseEntity<?> organizationMembers(@PathVariable String organizationId, @RequestParam(defaultValue = "1") String page, @RequestParam(defaultValue = "20") String pageSize) {
        return ok(PersonConverter.organizationMembers(service.organizationMembers(authentication.actor(), organizationId, page(page), size(pageSize))));
    }

    @GetMapping("/v1/admin/organizations/{organizationId}/members/{personId}")
    public ResponseEntity<?> localMember(@PathVariable String organizationId, @PathVariable String personId) {
        return resource(service.localMembership(authentication.actor(), personId, organizationId), PersonConverter::localMembership);
    }

    @PostMapping("/v1/admin/organizations/{organizationId}/members/{personId}/membership-changes")
    public ResponseEntity<?> changeLocalMember(@PathVariable String organizationId, @PathVariable String personId, @RequestBody PersonRequests.LocalMember body,
                                               @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestHeader(value = "If-Match", required = false) String match) {
        return resource(service.changeLocalMembership(authentication.actor(), personId, organizationId, body.command(), key, match), PersonConverter::localMembership);
    }

    @GetMapping("/v1/admin/organizations/{organizationId}/members/{personId}/membership-history")
    public ResponseEntity<?> localHistory(@PathVariable String organizationId, @PathVariable String personId,
                                          @RequestParam(defaultValue = "1") String page, @RequestParam(defaultValue = "20") String pageSize) {
        return ok(PersonConverter.page(service.localMembershipHistory(authentication.actor(), personId, organizationId, page(page), size(pageSize)), PersonConverter::localHistory));
    }

    @PostMapping("/v1/admin/organizations")
    public ResponseEntity<?> createOrganization(@RequestBody PersonRequests.CreateOrganization body, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return resource(service.createOrganization(authentication.actor(), body.command(), key), PersonConverter::organization);
    }

    @GetMapping("/v1/admin/organizations/{organizationId}")
    public ResponseEntity<?> organization(@PathVariable String organizationId) {
        return resource(service.organization(authentication.actor(), organizationId), PersonConverter::organization);
    }

    @PatchMapping("/v1/admin/organizations/{organizationId}")
    public ResponseEntity<?> editOrganization(@PathVariable String organizationId, @RequestBody PersonRequests.EditOrganization body, @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestHeader(value = "If-Match", required = false) String match) {
        return resource(service.updateOrganization(authentication.actor(), organizationId, body.command(), key, match), PersonConverter::organization);
    }

    @GetMapping("/v1/admin/person-management-scopes")
    public ResponseEntity<?> scopes(@RequestParam(defaultValue = "1") String page, @RequestParam(defaultValue = "20") String pageSize, @RequestParam(required = false) String granteeUserId, @RequestParam(required = false) String domain) {
        PersonBO.Domain d = null;
        if (domain != null) {
            try {
                d = PersonBO.Domain.valueOf(domain);
            } catch (IllegalArgumentException e) {
                throw invalid("domain");
            }
        }
        return ok(PersonConverter.page(service.scopes(authentication.actor(), page(page), size(pageSize), granteeUserId == null ? null : PersonFoundationService.userId(granteeUserId), d), PersonConverter::scope));
    }

    @PostMapping("/v1/admin/person-management-scopes")
    public ResponseEntity<?> grant(@RequestBody PersonRequests.Grant body, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return resource(service.grant(authentication.actor(), body.command(), key), PersonConverter::scope);
    }

    @GetMapping("/v1/admin/person-management-scopes/{scopeGrantId}")
    public ResponseEntity<?> scope(@PathVariable String scopeGrantId) {
        return resource(service.scope(authentication.actor(), scopeGrantId), PersonConverter::scope);
    }

    @PostMapping("/v1/admin/person-management-scopes/{scopeGrantId}:revoke")
    public ResponseEntity<?> revoke(@PathVariable String scopeGrantId, @RequestBody PersonRequests.Revoke body, @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestHeader(value = "If-Match", required = false) String match) {
        return resource(service.revoke(authentication.actor(), scopeGrantId, body.command(), key, match), PersonConverter::scope);
    }

    private static long user(String id) {
        return PersonFoundationService.userId(id);
    }

    static int page(String value) {
        try {
            if (!value.matches("[1-9][0-9]*")) throw invalid("page");
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw invalid("page");
        }
    }

    static int size(String value) {
        int size = page(value);
        if (size > 100) throw invalid("pageSize");
        return size;
    }

    private static <T> ResponseEntity<T> ok(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }

    private static <T, R> ResponseEntity<R> resource(PersonBO.Result<T> result, Function<T, R> convert) {
        var response = ResponseEntity.status(result.status()).cacheControl(CacheControl.noStore()).eTag(result.etag());
        if (result.location() != null) response.header(HttpHeaders.LOCATION, result.location());
        return response.body(convert.apply(result.value()));
    }
}
