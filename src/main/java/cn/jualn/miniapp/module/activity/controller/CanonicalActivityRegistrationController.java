package cn.jualn.miniapp.module.activity.controller;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.common.web.StrongEtag;
import cn.jualn.miniapp.module.activity.bo.ActivityRegistrationBO;
import cn.jualn.miniapp.module.activity.service.ActivityRegistrationService;
import cn.jualn.miniapp.module.activity.converter.PersonalRegistrationConverter;
import cn.jualn.miniapp.module.activity.dto.request.WriteRegistrationRequest;
import cn.jualn.miniapp.module.activity.vo.PersonalRegistrationVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/** HTTP adapter for the canonical personal registration resource. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/activities/{activityId}/registrations")
public class CanonicalActivityRegistrationController {
    private final ActivityRegistrationService service;
    private final PersonalRegistrationConverter converter;

    @PostMapping
    public ResponseEntity<PersonalRegistrationVO> create(
            @PathVariable Long activityId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody WriteRegistrationRequest request) {
        var result = service.submitContract(activityId, request.formVersion(), request.answers(), converter.versionCondition(ifMatch));
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return response(result.registration(), status,
                result.created() ? URI.create("/v1/activities/" + activityId + "/registrations/me") : null);
    }

    @GetMapping("/me")
    public ResponseEntity<PersonalRegistrationVO> mine(@PathVariable Long activityId) {
        ActivityRegistrationBO value = service.getMine(activityId);
        if (value == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "registration not found");
        }
        return response(value, HttpStatus.OK, null);
    }

    @PutMapping("/me")
    public ResponseEntity<PersonalRegistrationVO> replace(
            @PathVariable Long activityId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody WriteRegistrationRequest request) {
        return response(service.replaceMineContract(activityId, request.formVersion(), request.answers(), converter.versionCondition(ifMatch)),
                HttpStatus.OK, null);
    }

    @PostMapping("/me:cancel")
    public ResponseEntity<PersonalRegistrationVO> cancel(
            @PathVariable Long activityId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return response(service.cancelMineContract(activityId, converter.versionCondition(ifMatch)), HttpStatus.OK, null);
    }

    private ResponseEntity<PersonalRegistrationVO> response(ActivityRegistrationBO value, HttpStatus status, URI location) {
        var builder = ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .eTag(StrongEtag.of("activity-registration", value.getId(), version(value)));
        if (location != null) {
            builder.location(location);
        }
        return builder.body(converter.toVO(value));
    }

    private long version(ActivityRegistrationBO value) {
        return java.util.Objects.requireNonNull(value.getContractVersion(), "registration version");
    }
}
