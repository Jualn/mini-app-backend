package cn.jualn.miniapp.module.person.controller;

import cn.jualn.miniapp.module.person.bo.MentorWorkflowBO;
import cn.jualn.miniapp.module.person.service.qualification.MentorWorkflowService;
import cn.jualn.miniapp.module.person.service.binding.ManagedPersonBindingService;
import org.springframework.http.MediaType;
import cn.dev33.satoken.stp.StpUtil;
import cn.jualn.miniapp.module.person.bo.QualificationBO.Apply;
import cn.jualn.miniapp.module.person.bo.QualificationBO.Commit;
import cn.jualn.miniapp.module.person.bo.QualificationBO.Correction;
import cn.jualn.miniapp.module.person.bo.QualificationBO.EntryQuery;
import cn.jualn.miniapp.module.person.bo.QualificationBO.ProfileWrite;
import cn.jualn.miniapp.module.person.bo.QualificationBO.QualificationChange;
import cn.jualn.miniapp.module.person.bo.QualificationBO.Resource;

import cn.jualn.miniapp.module.person.bo.QualificationBO.Mapping;
import cn.jualn.miniapp.module.person.converter.QualificationConverter;
import cn.jualn.miniapp.module.person.service.qualification.MentorManagementService;
import cn.jualn.miniapp.module.person.service.roster.PartyRosterService;
import cn.jualn.miniapp.module.person.service.access.PersonAuthentication;
import cn.jualn.miniapp.module.person.service.facts.PersonFoundationService;
import cn.jualn.miniapp.module.person.service.support.PersonnelValues;
import cn.jualn.miniapp.module.person.service.roster.RosterApplicationThrottle;
import cn.jualn.miniapp.module.person.service.roster.RosterWorkbook;

import cn.jualn.miniapp.module.user.service.UserAccountService;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.fasterxml.jackson.core.StreamReadFeature;

import java.util.Set;
import java.util.function.Function;
import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
public class QualificationController {
    private final PartyRosterService roster;
    private final MentorManagementService mentors;
    private final MentorWorkflowService mentorWorkflow;
    private final ManagedPersonBindingService mentorBindings;
    private final PersonAuthentication authentication;
    private final UserAccountService users;
    private final RosterApplicationThrottle throttle;
    private static final ObjectMapper REQUESTS = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .findAndRegisterModules().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    static {
        REQUESTS.disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS);
        REQUESTS.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        REQUESTS.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        var textual=REQUESTS.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual);
        for(var shape:java.util.List.of(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer,com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float,com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean)) textual.setCoercion(shape,com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
    }
    static <T> T body(byte[] bytes, Class<T> type) {
        return body(bytes, type, 65536);
    }

    static <T> T body(byte[] bytes, Class<T> type, int maxBytes) {
        try {
            if (bytes == null || bytes.length > maxBytes) throw new IllegalArgumentException();
            return REQUESTS.readValue(bytes, type);
        } catch (Exception e) {
            throw ContractProblemException.validation(new ContractProblemException.Violation("body", "/", "INVALID_VALUE", "Invalid personnel request"));
        }
    }

    private long admin() {
        return authentication.actor();
    }

    private long mini() {
        StpUtil.checkLogin();
        long user = StpUtil.getLoginIdAsLong();
        users.assertLoginAllowed(user);
        return user;
    }

    private static long user(String s) {
        return PersonFoundationService.userId(s);
    }

    private static <T, R> ResponseEntity<R> resource(Resource<T> r, Function<T, R> convert) {
        var b = ResponseEntity.status(r.status()).cacheControl(CacheControl.noStore()).eTag(r.etag());
        if (r.location() != null) b.location(URI.create(r.location()));
        return b.body(convert.apply(r.value()));
    }

    private static <T> ResponseEntity<T> ok(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }

    private static void query(HttpServletRequest request, Set<String> allowed) {
        if (!allowed.containsAll(request.getParameterMap().keySet()))
            throw ContractProblemException.validation(new ContractProblemException.Violation("query", "", "INVALID_VALUE", "Unexpected query parameter"));
    }

    @GetMapping("/v1/admin/party-roster-imports")
    public ResponseEntity<?> imports(@RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "20") int pageSize,
                                     @RequestParam(required = false) String state,
                                     HttpServletRequest request) {
        long actor = admin();
        query(request, java.util.Set.of("page", "pageSize", "state"));
        return ok(QualificationConverter.page(roster.imports(actor, page, pageSize, state), QualificationConverter::rosterImport));
    }

    @GetMapping("/v1/admin/party-roster-imports/creation-receipt")
    public ResponseEntity<?> creationReceipt(@RequestParam String organizationId,
                                             @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                             HttpServletRequest request) {
        long actor = admin();
        query(request, Set.of("organizationId"));
        return ok(QualificationConverter.recovery(roster.creationReceipt(actor, organizationId, key)));
    }

    @GetMapping("/v1/users/me/party-identity")
    public ResponseEntity<?> myIdentity(HttpServletRequest request) {
        long user = mini();
        query(request, Set.of());
        return ok(QualificationConverter.myIdentity(roster.myIdentity(user)));
    }

    @PostMapping(value = "/v1/admin/party-roster-imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@RequestPart MultipartFile file,
                                    @RequestParam String organizationId,
                                    @RequestParam String sourceReference,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String key) throws java.io.IOException {
        long actor = admin();
        if (file.getSize() > RosterWorkbook.MAX_BYTES)
            throw PersonnelValues.problem(HttpStatus.PAYLOAD_TOO_LARGE, "payload-too-large");
        return resource(roster.upload(actor, organizationId, sourceReference, file.getOriginalFilename(), file.getContentType(), file.getBytes(), key), QualificationConverter::rosterImport);
    }

    @GetMapping("/v1/admin/party-roster-imports/{importId}")
    public ResponseEntity<?> getImport(@PathVariable String importId) {
        return resource(roster.getImport(admin(), importId), QualificationConverter::rosterImport);
    }

    @PutMapping("/v1/admin/party-roster-imports/{importId}/mapping")
    public ResponseEntity<?> map(@PathVariable String importId, @RequestBody byte[] input, @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestHeader(value = "If-Match", required = false) String match) {
        long actor = admin();
        return resource(roster.map(actor, importId, body(input, Mapping.class), key, match), QualificationConverter::rosterImport);
    }

    @GetMapping("/v1/admin/party-roster-imports/{importId}/rows")
    public ResponseEntity<?> rows(@PathVariable String importId, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize,
                                  @RequestParam(defaultValue = "INCREMENTAL") String mode) {
        return resource(roster.rows(admin(), importId, page, pageSize, mode), QualificationConverter::preview);
    }

    @GetMapping("/v1/admin/party-roster-imports/{importId}/results")
    public ResponseEntity<?> importResults(@PathVariable String importId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return ok(roster.results(admin(), importId, page, pageSize));
    }

    @GetMapping("/v1/admin/party-roster-imports/{importId}/sheets")
    public ResponseEntity<?> sheets(@PathVariable String importId) {
        return resource(roster.sheets(admin(),importId),QualificationConverter::sheets);
    }

    @GetMapping("/v1/admin/party-roster-imports/{importId}/columns")
    public ResponseEntity<?> columns(@PathVariable String importId,@RequestParam int sheetIndex,@RequestParam int headerRow,jakarta.servlet.http.HttpServletRequest request) {
        long actor=admin();query(request,java.util.Set.of("sheetIndex","headerRow"));
        return resource(roster.columns(actor,importId,sheetIndex,headerRow),QualificationConverter::columns);
    }

    @PostMapping("/v1/admin/party-roster-imports/{importId}:commit")
    public ResponseEntity<?> commit(@PathVariable String importId, @RequestBody byte[] input, @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestHeader(value = "If-Match", required = false) String match) {
        long actor = admin();
        return resource(roster.commit(actor, importId, body(input, Commit.class), key, match), QualificationConverter::rosterImport);
    }

    @GetMapping("/v1/admin/party-roster-entries")
    public ResponseEntity<?> entries(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize, @RequestParam(required = false) String organizationId, @RequestParam(required = false) String linkState, @RequestParam(required = false) String stageId, @RequestParam(required = false) String baselineStatus, @RequestParam(required = false) String q, @RequestParam(required = false) String identityVerification) {
        return ok(QualificationConverter.page(roster.entries(admin(), page, pageSize, new EntryQuery(organizationId, linkState, stageId, baselineStatus, q, identityVerification)), QualificationConverter::entry));
    }

    @GetMapping("/v1/admin/party-roster-entries/{rosterEntryId}")
    public ResponseEntity<?> entry(@PathVariable String rosterEntryId) {
        return resource(roster.entry(admin(), rosterEntryId), QualificationConverter::entry);
    }

    @PutMapping("/v1/admin/party-roster-entries/{rosterEntryId}")
    public ResponseEntity<?> correct(@PathVariable String rosterEntryId, @RequestBody byte[] input, @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestHeader(value = "If-Match", required = false) String match) {
        long actor = admin();
        return resource(roster.correct(actor, rosterEntryId, body(input, Correction.class), key, match), QualificationConverter::entry);
    }

    @GetMapping("/v1/admin/party-roster-entries/{rosterEntryId}/history")
    public ResponseEntity<?> rosterHistory(@PathVariable String rosterEntryId, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return ok(QualificationConverter.page(roster.history(admin(), rosterEntryId, page, pageSize), QualificationConverter::audit));
    }

    @PostMapping("/v1/users/me/party-identity-applications")
    public ResponseEntity<?> apply(@RequestBody byte[] input, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        long user = mini();
        throttle.require(user);
        return resource(roster.apply(user, body(input, Apply.class), key), QualificationConverter::application);
    }

    @GetMapping("/v1/users/me/party-identity-applications")
    public ResponseEntity<?> applications(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return ok(QualificationConverter.page(roster.applications(mini(), page, pageSize), QualificationConverter::application));
    }

    @GetMapping("/v1/admin/mentor-candidates")
    public ResponseEntity<?> mentorCandidates(@RequestParam String q, @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int pageSize) {
        return ok(mentorWorkflow.candidates(admin(),q,page,pageSize));
    }

    @PostMapping("/v1/admin/mentors")
    public ResponseEntity<?> createMentor(@RequestBody byte[] input,@RequestHeader(value="Idempotency-Key",required=false) String key) {
        return resource(mentorWorkflow.create(admin(),body(input,MentorWorkflowBO.Create.class),key),QualificationConverter::mentor);
    }

    @GetMapping("/v1/admin/mentors/{personId}/account-options")
    public ResponseEntity<?> mentorAccounts(@PathVariable String personId,@RequestParam String q,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {
        return ok(mentorWorkflow.accounts(admin(),personId,q,page,pageSize));
    }

    @GetMapping("/v1/admin/mentors/{personId}/account-binding")
    public ResponseEntity<?> mentorBinding(@PathVariable String personId) {
        var result=mentorBindings.getMentorBinding(admin(),personId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(result.etag()).body(result.value());
    }

    @PostMapping("/v1/admin/mentors/{personId}/account-binding")
    public ResponseEntity<?> changeMentorBinding(@PathVariable String personId,@RequestBody byte[] input,
            @RequestHeader(value="Idempotency-Key",required=false) String key,@RequestHeader(value="If-Match",required=false) String match) {
        var result=mentorBindings.changeMentorBinding(admin(),personId,body(input,MentorWorkflowBO.BindingChange.class),key,match);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(result.etag()).body(result.value());
    }

    @GetMapping("/v1/admin/mentors")
    public ResponseEntity<?> mentors(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize, @RequestParam(required = false) String q, @RequestParam(required = false) String qualificationStatus, @RequestParam(required = false) String bindingState) {
        return ok(QualificationConverter.page(mentors.list(admin(), page, pageSize, q, qualificationStatus, bindingState), QualificationConverter::mentor));
    }

    @GetMapping("/v1/admin/mentors/{personId}")
    public ResponseEntity<?> mentor(@PathVariable String personId) {
        return resource(mentors.get(admin(), personId), QualificationConverter::mentor);
    }

    @PutMapping("/v1/admin/mentors/{personId}")
    public ResponseEntity<?> put(@PathVariable String personId, @RequestBody byte[] input, @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestHeader(value = "If-Match", required = false) String match, @RequestHeader(value = "If-None-Match", required = false) String none) {
        long actor = admin();
        return resource(mentors.put(actor, personId, body(input, ProfileWrite.class), key, match, none), QualificationConverter::mentor);
    }

    @PostMapping("/v1/admin/mentors/{personId}/qualification-changes")
    public ResponseEntity<?> qualification(@PathVariable String personId, @RequestBody byte[] input, @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestHeader(value = "If-Match", required = false) String match) {
        long actor = admin();
        return resource(mentors.change(actor, personId, body(input, QualificationChange.class), key, match), QualificationConverter::mentor);
    }

    @GetMapping("/v1/admin/mentors/{personId}/history")
    public ResponseEntity<?> mentorHistory(@PathVariable String personId, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return ok(QualificationConverter.page(mentors.history(admin(), personId, page, pageSize), QualificationConverter::audit));
    }

    @GetMapping("/v1/admin/mentors/{personId}/answers")
    public ResponseEntity<?> answers(@PathVariable String personId, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize, @RequestParam(required = false) String authorUserId) {
        return ok(QualificationConverter.page(mentors.answers(admin(), personId, page, pageSize, authorUserId==null?null:user(authorUserId)), QualificationConverter::answer));
    }

    @GetMapping("/v1/mentors/{userId}")
    public ResponseEntity<?> certified(@PathVariable String userId) {
        return ok(QualificationConverter.certified(mentors.certified(mini(), user(userId))));
    }
}
