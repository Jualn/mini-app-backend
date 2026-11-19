package cn.jualn.miniapp.module.points.controller;

import cn.jualn.miniapp.module.points.bo.PointsBO;
import cn.jualn.miniapp.module.points.dto.PointsRequests;
import cn.jualn.miniapp.module.points.service.PointsDependencies;
import cn.jualn.miniapp.module.points.service.PointsService;
import cn.jualn.miniapp.module.points.vo.PointsVO;
import jakarta.servlet.http.HttpServletRequest;

import java.net.URI;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static cn.jualn.miniapp.module.points.PointsProblems.invalidParameter;
import static cn.jualn.miniapp.module.points.PointsProblems.missing;

@RestController
@RequiredArgsConstructor
public class PointsController {
    private final PointsService points;
    private final PointsDependencies dependencies;

    @ModelAttribute
    public void authenticate(HttpServletRequest request) {
        boolean admin = request.getRequestURI().startsWith(request.getContextPath() + "/v1/admin/");
        var action = !admin ? PointsBO.Action.MY_READ : request.getRequestURI().endsWith(":reverse") ? PointsBO.Action.REVERSE : request.getRequestURI().endsWith(":correct") ? PointsBO.Action.CORRECT :
                request.getRequestURI().endsWith("/decisions") || request.getRequestURI().contains("/files/") ? PointsBO.Action.REVIEW : PointsBO.Action.READ;
        dependencies.actor(action);
    }

    @GetMapping("/v1/users/me/points/eligibility")
    public ResponseEntity<PointsVO.Eligibility> eligibility() {
        var value = points.eligibility();
        return ok(new PointsVO.Eligibility(value.eligible(), PointsVO.time(value.evaluatedAt()), value.reason()));
    }

    @GetMapping("/v1/users/me/points/accepting-organizations")
    public ResponseEntity<PointsVO.AcceptingOrganizations> acceptingOrganizations() {
        var result = points.acceptingOrganizations();
        return ok(new PointsVO.AcceptingOrganizations(PointsVO.time(result.evaluatedAt()), result.items().stream()
                .map(o -> new PointsVO.AcceptingOrganization(o.organizationId(), o.name(), o.membershipId())).toList()));
    }

    @PostMapping("/v1/users/me/points/applications/{applicationId}:close")
    public ResponseEntity<PointsVO.Application> close(@PathVariable String applicationId, @RequestBody PointsRequests.Close body, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return ok(PointsVO.application(points.close(resource(applicationId), body.reason(), key)));
    }

    @PostMapping("/v1/users/me/points/applications")
    public ResponseEntity<PointsVO.Application> create(@RequestBody PointsRequests.Create body, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        var app = points.create(body.command(), key);
        return ResponseEntity.created(URI.create("/v1/users/me/points/applications/" + app.applicationId())).cacheControl(CacheControl.noStore()).body(PointsVO.application(app));
    }

    @GetMapping("/v1/users/me/points/applications")
    public ResponseEntity<PointsVO.Page<PointsVO.Application>> applications(@RequestParam(defaultValue = "1") String page, @RequestParam(defaultValue = "20") String pageSize, @RequestParam(required = false) String status) {
        var result = points.applications(query(page, pageSize, status, null));
        return ok(new PointsVO.Page<>(result.items().stream().map(PointsVO::application).toList(), result.page(), result.pageSize(), result.totalItems()));
    }

    @GetMapping("/v1/users/me/points/applications/{applicationId}")
    public ResponseEntity<PointsVO.Application> mine(@PathVariable String applicationId) {
        return ok(PointsVO.application(points.mine(resource(applicationId))));
    }

    @GetMapping("/v1/users/me/points/summary")
    public ResponseEntity<PointsVO.Summary> summary() {
        return ok(PointsVO.summary(points.summary()));
    }

    @GetMapping("/v1/users/me/points/ledger")
    public ResponseEntity<PointsVO.Page<PointsVO.Ledger>> ledger(@RequestParam(defaultValue = "1") String page, @RequestParam(defaultValue = "20") String pageSize, @RequestParam(required = false) String kind, @RequestParam(required = false) String applicationId) {
        var base = query(page, pageSize, null, kind);
        var result = points.ledgers(new PointsBO.Query(base.page(), base.pageSize(), null, base.kind(), applicationId == null ? null : resource(applicationId)));
        return ok(new PointsVO.Page<>(result.items().stream().map(PointsVO::ledger).toList(), result.page(), result.pageSize(), result.totalItems()));
    }

    @GetMapping("/v1/admin/points/applications/{applicationId}")
    public ResponseEntity<PointsVO.Application> adminApplication(@PathVariable String applicationId) {
        return ok(PointsVO.application(points.adminApplication(resource(applicationId))));
    }

    @PostMapping("/v1/admin/points/applications/{applicationId}/decisions")
    public ResponseEntity<PointsVO.Application> review(@PathVariable String applicationId, @RequestBody PointsRequests.Review body, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return ok(PointsVO.application(points.review(resource(applicationId), body.command(), key)));
    }

    @PostMapping("/v1/admin/points/ledger/{ledgerId}:reverse")
    public ResponseEntity<PointsVO.Ledger> reverse(@PathVariable String ledgerId, @RequestBody PointsRequests.Reversal body, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        var result = points.reverse(resource(ledgerId), new PointsBO.Reversal(body.reason(), body.basis()), key);
        return ResponseEntity.created(URI.create("/v1/admin/points/ledger/" + result.ledgerId())).cacheControl(CacheControl.noStore()).body(PointsVO.ledger(result));
    }

    @PostMapping("/v1/admin/points/ledger/{ledgerId}:correct")
    public ResponseEntity<PointsVO.Correction> correct(@PathVariable String ledgerId, @RequestBody PointsRequests.Correction body, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        var result = points.correct(resource(ledgerId), body.command(), key);
        return ResponseEntity.created(URI.create("/v1/admin/points/corrections/" + result.correctionId())).cacheControl(CacheControl.noStore()).body(PointsVO.correction(result));
    }

    @GetMapping("/v1/admin/points/corrections/{correctionId}")
    public ResponseEntity<PointsVO.Correction> correction(@PathVariable String correctionId) {
        return ok(PointsVO.correction(points.correction(correctionId)));
    }

    @GetMapping("/v1/admin/points/applications/{applicationId}/ledger")
    public ResponseEntity<PointsVO.Page<PointsVO.Ledger>> applicationLedger(@PathVariable String applicationId, @RequestParam(defaultValue = "1") String page, @RequestParam(defaultValue = "20") String pageSize, @RequestParam(required = false) String kind) {
        var result = points.applicationLedger(resource(applicationId), query(page, pageSize, null, kind));
        return ok(new PointsVO.Page<>(result.items().stream().map(PointsVO::ledger).toList(), result.page(), result.pageSize(), result.totalItems()));
    }

    @GetMapping("/v1/admin/points/ledger/{ledgerId}")
    public ResponseEntity<PointsVO.Ledger> adminLedger(@PathVariable String ledgerId) {
        return ok(PointsVO.ledger(points.adminLedger(resource(ledgerId))));
    }

    @GetMapping("/v1/admin/points/applications")
    public ResponseEntity<PointsVO.Page<PointsVO.Application>> adminApplications(@RequestParam(defaultValue = "1") String page,
                                                                                 @RequestParam(defaultValue = "20") String pageSize, @RequestParam(required = false) String status,
                                                                                 @RequestParam(required = false) String personId, @RequestParam(required = false) String organizationId) {
        var result = points.adminApplications(query(page, pageSize, status, null), personId, organizationId);
        return ok(new PointsVO.Page<>(result.items().stream().map(PointsVO::application).toList(), result.page(), result.pageSize(), result.totalItems()));
    }

    @GetMapping("/v1/admin/points/statistics/persons")
    public ResponseEntity<PointsVO.StatisticsPage<PointsVO.PersonStatistics>> personStatistics(@RequestParam(defaultValue = "1") String page,
                                                                                               @RequestParam(defaultValue = "20") String pageSize, @RequestParam(required = false) String personId, @RequestParam(required = false) String organizationId) {
        var result = points.personStatistics(query(page, pageSize, null, null), personId, organizationId);
        return ok(new PointsVO.StatisticsPage<>(result.items().stream().map(PointsVO::personStatistics).toList(), result.page(), result.pageSize(), result.totalItems(), result.membershipBasis(), PointsVO.time(result.evaluatedAt())));
    }

    @GetMapping("/v1/admin/points/statistics/organizations")
    public ResponseEntity<PointsVO.StatisticsPage<PointsVO.OrganizationStatistics>> organizationStatistics(@RequestParam(defaultValue = "1") String page,
                                                                                                           @RequestParam(defaultValue = "20") String pageSize, @RequestParam(required = false) String organizationId) {
        var result = points.organizationStatistics(query(page, pageSize, null, null), organizationId);
        return ok(new PointsVO.StatisticsPage<>(result.items().stream().map(PointsVO::organizationStatistics).toList(), result.page(), result.pageSize(), result.totalItems(), result.membershipBasis(), PointsVO.time(result.evaluatedAt())));
    }

    @GetMapping("/v1/admin/points/statistics/accepting-organizations")
    public ResponseEntity<PointsVO.AcceptanceStatisticsPage> acceptanceStatistics(@RequestParam(defaultValue = "1") String page, @RequestParam(defaultValue = "20") String pageSize, @RequestParam(required = false) String organizationId) {
        var result = points.acceptanceStatistics(query(page, pageSize, null, null), organizationId);
        return ok(new PointsVO.AcceptanceStatisticsPage(result.items().stream().map(r -> new PointsVO.AcceptanceStatistics(r.organizationId(), r.applicationCount(), r.balance(), r.totalGranted(), r.totalReversed())).toList(), result.page(), result.pageSize(), result.totalItems(), result.membershipBasis(), PointsVO.time(result.evaluatedAt())));
    }

    private static Long optionalUser(String user) {
        return user == null ? null : resource(user);
    }

    private static <T> ResponseEntity<T> ok(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }

    private static long resource(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw missing();
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalidId) {
            throw missing();
        }
    }

    private static int number(String value, int max, String field) {
        if (value == null || !value.matches("[0-9]+")) throw invalidParameter("query", field);
        try {
            int result = Integer.parseInt(value);
            if (result < 1 || result > max) throw invalidParameter("query", field);
            return result;
        } catch (NumberFormatException invalidNumber) {
            throw invalidParameter("query", field);
        }
    }

    private static PointsBO.Query query(String page, String pageSize, String status, String kind) {
        PointsBO.Status state = null;
        PointsBO.Kind type = null;
        if (status != null) {
            try {
                state = PointsBO.Status.valueOf(status);
            } catch (IllegalArgumentException invalidState) {
                throw invalidParameter("query", "status");
            }
        }
        if (kind != null) {
            try {
                type = PointsBO.Kind.valueOf(kind);
            } catch (IllegalArgumentException invalidKind) {
                throw invalidParameter("query", "kind");
            }
        }
        return new PointsBO.Query(number(page, Integer.MAX_VALUE, "page"), number(pageSize, 100, "pageSize"), state, type);
    }
}
