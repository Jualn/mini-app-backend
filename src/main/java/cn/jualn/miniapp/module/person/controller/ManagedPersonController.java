package cn.jualn.miniapp.module.person.controller;

import cn.jualn.miniapp.module.person.bo.PersonBO.Result;
import cn.jualn.miniapp.module.person.converter.ManagedPersonConverter;
import cn.jualn.miniapp.module.person.dto.ManagedPersonRequests;
import cn.jualn.miniapp.module.person.service.binding.ManagedPersonBindingService;
import cn.jualn.miniapp.module.person.service.profile.ManagedPersonIdentifierService;
import cn.jualn.miniapp.module.person.service.profile.ManagedPersonService;
import cn.jualn.miniapp.module.person.service.access.PersonAuthentication;
import cn.jualn.miniapp.module.person.service.facts.PersonFoundationService;
import cn.jualn.miniapp.module.person.vo.ManagedPersonVO;

import java.util.function.Function;

import static cn.jualn.miniapp.module.person.converter.ManagedPersonConverter.recovery;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static cn.jualn.miniapp.module.person.converter.ManagedPersonConverter.binding;
import static cn.jualn.miniapp.module.person.converter.ManagedPersonConverter.bindingChange;
import static cn.jualn.miniapp.module.person.converter.ManagedPersonConverter.bindings;
import static cn.jualn.miniapp.module.person.converter.ManagedPersonConverter.history;
import static cn.jualn.miniapp.module.person.converter.ManagedPersonConverter.profile;
import static cn.jualn.miniapp.module.person.converter.ManagedPersonConverter.registration;
import static cn.jualn.miniapp.module.person.converter.ManagedPersonConverter.slot;

@RestController
@RequiredArgsConstructor
public class ManagedPersonController {
    private final ManagedPersonService service;
    private final ManagedPersonIdentifierService identifiers;
    private final ManagedPersonBindingService bindings;
    private final PersonAuthentication authentication;

    @ModelAttribute
    public void authenticate() {
        authentication.actor();
    }

    @PostMapping("/v1/admin/persons")
    public ResponseEntity<?> create(@RequestBody ManagedPersonRequests.Create body, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return resource(service.createManagedPerson(authentication.actor(), body.command(), key), cn.jualn.miniapp.module.person.converter.ManagedPersonConverter::profile);
    }

    @GetMapping("/v1/admin/persons/{personId}")
    public ResponseEntity<?> get(@PathVariable String personId) {
        return resource(service.getManagedPerson(authentication.actor(), personId), cn.jualn.miniapp.module.person.converter.ManagedPersonConverter::profile);
    }

    @PatchMapping("/v1/admin/persons/{personId}")
    public ResponseEntity<?> name(@PathVariable String personId, @RequestBody ManagedPersonRequests.Name body,
                                  @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestHeader(value = "If-Match", required = false) String match) {
        return resource(service.updateManagedPersonName(authentication.actor(), personId, body.command(), key, match), cn.jualn.miniapp.module.person.converter.ManagedPersonConverter::profile);
    }

    @PostMapping("/v1/admin/persons/{personId}/lifecycle-changes")
    public ResponseEntity<?> lifecycle(@PathVariable String personId, @RequestBody ManagedPersonRequests.Lifecycle body,
                                       @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestHeader(value = "If-Match", required = false) String match) {
        return resource(service.changeManagedPersonLifecycle(authentication.actor(), personId, body.command(), key, match), cn.jualn.miniapp.module.person.converter.ManagedPersonConverter::profile);
    }

    @GetMapping("/v1/admin/persons/{personId}/history")
    public ResponseEntity<?> history(@PathVariable String personId, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return ok(cn.jualn.miniapp.module.person.converter.ManagedPersonConverter.history(service.listManagedPersonHistory(authentication.actor(), personId, page, pageSize)));
    }

    @GetMapping("/v1/admin/persons/{personId}/major-class-registration")
    public ResponseEntity<?> registration(@PathVariable String personId, @RequestParam(required = false) String organizationId) {
        return resource(service.getPersonMajorClassRegistration(authentication.actor(), personId, organizationId), ManagedPersonConverter::registration);
    }

    @PatchMapping(value = "/v1/admin/persons/{personId}/major-class-registration", consumes = "application/merge-patch+json")
    public ResponseEntity<?> registration(@PathVariable String personId, @RequestParam(required = false) String organizationId, @RequestBody ManagedPersonRequests.Registration body,
                                          @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestHeader(value = "If-Match", required = false) String match) {
        return resource(service.updatePersonMajorClassRegistration(authentication.actor(), personId, organizationId, body.command(), key, match), ManagedPersonConverter::registration);
    }

    @GetMapping("/v1/admin/person-identifier-namespaces")
    public ResponseEntity<?> namespaces() {
        return ok(new ManagedPersonVO.NamespaceList(identifiers.listPersonIdentifierNamespaces(authentication.actor())));
    }

    @GetMapping("/v1/admin/persons/{personId}/identifiers")
    public ResponseEntity<?> identifiers(@PathVariable String personId) {
        return resource(identifiers.listPersonIdentifiers(authentication.actor(), personId), Function.identity());
    }

    @PostMapping("/v1/admin/persons/{personId}/identifiers")
    public ResponseEntity<?> identifier(@PathVariable String personId, @RequestBody ManagedPersonRequests.IdentifierCreate body, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return resource(identifiers.createPersonIdentifier(authentication.actor(), personId, body.command(), key), Function.identity());
    }

    @PostMapping("/v1/admin/persons/{personId}/identifiers/{identifierId}/changes")
    public ResponseEntity<?> identifier(@PathVariable String personId, @PathVariable String identifierId, @RequestBody ManagedPersonRequests.IdentifierChange body,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestHeader(value = "If-Match", required = false) String match) {
        return resource(identifiers.changePersonIdentifier(authentication.actor(), personId, identifierId, body.command(), key, match), Function.identity());
    }

    @GetMapping("/v1/admin/persons/{personId}/identifiers/{identifierId}/value")
    public ResponseEntity<?> identifierValue(@PathVariable String personId, @PathVariable String identifierId, @RequestParam(required = false) String version) {
        return resource(identifiers.getPersonIdentifierValue(authentication.actor(), personId, identifierId, version), Function.identity());
    }

    @GetMapping("/v1/admin/persons/{personId}/binding")
    public ResponseEntity<?> bindingSlot(@PathVariable String personId) {
        return resource(bindings.getPersonAccountBinding(authentication.actor(), personId), cn.jualn.miniapp.module.person.converter.ManagedPersonConverter::slot);
    }

    @GetMapping("/v1/admin/persons/{personId}/bindings")
    public ResponseEntity<?> bindingHistory(@PathVariable String personId, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return ok(cn.jualn.miniapp.module.person.converter.ManagedPersonConverter.bindings(bindings.listPersonAccountBindings(authentication.actor(), personId, page, pageSize)));
    }

    @PostMapping("/v1/admin/user-person-bindings")
    public ResponseEntity<?> bind(@RequestBody ManagedPersonRequests.Bind body, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return resource(bindings.createUserPersonBinding(authentication.actor(), body.command(), key), cn.jualn.miniapp.module.person.converter.ManagedPersonConverter::binding);
    }

    @GetMapping("/v1/admin/user-person-bindings/{bindingId}")
    public ResponseEntity<?> binding(@PathVariable String bindingId) {
        return resource(bindings.getUserPersonBinding(authentication.actor(), bindingId), cn.jualn.miniapp.module.person.converter.ManagedPersonConverter::binding);
    }

    @PostMapping("/v1/admin/user-person-bindings/{bindingId}:end")
    public ResponseEntity<?> end(@PathVariable String bindingId, @RequestBody ManagedPersonRequests.End body,
                                 @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestHeader(value = "If-Match", required = false) String match) {
        return resource(bindings.endUserPersonBinding(authentication.actor(), bindingId, body.command(), key, match), cn.jualn.miniapp.module.person.converter.ManagedPersonConverter::binding);
    }

    @PostMapping("/v1/admin/user-person-binding-changes")
    public ResponseEntity<?> reassociate(@RequestBody ManagedPersonRequests.Reassociate body, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return resource(bindings.reassociateUserPersonBinding(authentication.actor(), body.command(), key), cn.jualn.miniapp.module.person.converter.ManagedPersonConverter::bindingChange);
    }

    @GetMapping("/v1/admin/person-account-bindings/{userId}")
    public ResponseEntity<?> account(@PathVariable String userId) {
        return resource(bindings.getAccountPersonBinding(authentication.actor(), PersonFoundationService.userId(userId)), Function.identity());
    }

    @GetMapping("/v1/admin/person-command-receipts")
    public ResponseEntity<?> receipt(@RequestParam String operationId, @RequestParam String target, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return ok(recovery(bindings.getMyPersonCommandReceipt(authentication.actor(), operationId, target, key)));
    }

    static <T, R> ResponseEntity<R> resource(Result<T> result, Function<T, R> convert) {
        var response = ResponseEntity.status(result.status()).cacheControl(CacheControl.noStore()).eTag(result.etag());
        if (result.location() != null) response.header(HttpHeaders.LOCATION, result.location());
        return response.body(convert.apply(result.value()));
    }

    static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
