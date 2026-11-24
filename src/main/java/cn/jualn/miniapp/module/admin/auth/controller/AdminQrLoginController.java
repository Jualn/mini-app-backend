package cn.jualn.miniapp.module.admin.auth.controller;

import cn.dev33.satoken.stp.StpUtil;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.admin.auth.converter.AdminQrLoginConverter;
import cn.jualn.miniapp.module.admin.auth.dto.qrlogin.AdminQrSceneRequest;
import cn.jualn.miniapp.module.admin.auth.service.AdminQrLoginService;
import cn.jualn.miniapp.module.admin.auth.support.AdminBrowserSession;
import cn.jualn.miniapp.module.admin.auth.vo.qrlogin.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping(value = "/v1/admin/auth", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class AdminQrLoginController {
    private static final String SECRET = "X-Admin-Login-Secret";
    private final AdminQrLoginService service;
    private final AdminQrLoginConverter converter;

    @PostMapping("/qr-login-sessions")
    public ResponseEntity<AdminQrCreatedVO> create(@RequestBody(required = false) byte[] body, HttpServletRequest request) {
        noBody(body);
        var created = converter.created(service.createSession(cn.jualn.miniapp.web.RequestPeer.address(request)));
        return ResponseEntity.created(URI.create("/v1/admin/auth/qr-login-sessions/" + created.session().sessionId())).body(created);
    }

    @GetMapping("/qr-login-sessions/{sessionId}")
    public AdminQrSessionVO query(@PathVariable String sessionId,
                                  @RequestHeader(name = SECRET, required = false) String secret, HttpServletRequest request) {
        return converter.session(service.querySession(sessionId, secret, cn.jualn.miniapp.web.RequestPeer.address(request)));
    }

    @GetMapping(value = "/qr-login-sessions/{sessionId}/code", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> code(@PathVariable String sessionId,
                                       @RequestHeader(name = SECRET, required = false) String secret, HttpServletRequest request) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(service.readCode(sessionId, secret, cn.jualn.miniapp.web.RequestPeer.address(request)));
    }

    @PostMapping("/qr-login-sessions/{sessionId}:consume")
    public ResponseEntity<AdminQrLoginResultVO> consume(@PathVariable String sessionId,
                                        @RequestHeader(name = SECRET, required = false) String secret,
                                        @RequestHeader(name = "X-Admin-Identity-Representation", required = false) String representation,
                                        @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        noBody(body);
        boolean browserSession = request.getHeader(AdminBrowserSession.HEADER) != null;
        if (browserSession) AdminBrowserSession.requireSameOrigin(request);
        var result = service.consumeSession(sessionId, secret, cn.jualn.miniapp.web.RequestPeer.address(request),
                representation == null ? "RBAC_V1" : representation);
        var response = ResponseEntity.ok();
        if (browserSession) {
            long remainingSeconds = (result.tokenExpiresAt() - System.currentTimeMillis()) / 1000;
            if (remainingSeconds > 0)
                response.header(HttpHeaders.SET_COOKIE, AdminBrowserSession.cookie(result.token(), remainingSeconds).toString());
        }
        return response.body(converter.result(result));
    }

    @PostMapping("/qr-login-sessions/{sessionId}:cancel")
    public AdminQrSessionVO cancel(@PathVariable String sessionId,
                                   @RequestHeader(name = SECRET, required = false) String secret,
                                   @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        noBody(body);
        return converter.session(service.cancelSession(sessionId, secret, cn.jualn.miniapp.web.RequestPeer.address(request)));
    }

    @PostMapping("/qr-login-scans")
    public AdminQrScanVO scan(@Valid @RequestBody AdminQrSceneRequest body, HttpServletRequest request) {
        return converter.scan(service.scanSession(body.getSceneCode(), StpUtil.getLoginIdAsLong(), cn.jualn.miniapp.web.RequestPeer.address(request)));
    }

    @PostMapping("/qr-login-scans:confirm")
    public AdminQrScanVO confirm(@Valid @RequestBody AdminQrSceneRequest body, HttpServletRequest request) {
        return converter.scan(service.confirmSession(body.getSceneCode(), StpUtil.getLoginIdAsLong(), cn.jualn.miniapp.web.RequestPeer.address(request)));
    }

    @PostMapping("/qr-login-scans:reject")
    public AdminQrScanVO reject(@Valid @RequestBody AdminQrSceneRequest body, HttpServletRequest request) {
        return converter.scan(service.rejectSession(body.getSceneCode(), StpUtil.getLoginIdAsLong(), cn.jualn.miniapp.web.RequestPeer.address(request)));
    }

    private void noBody(byte[] body) {
        if (body != null && body.length != 0) throw ContractProblemException.validation(
                new ContractProblemException.Violation("body", "/", "NOT_ALLOWED", "Request body is not accepted"));
    }
}
