package cn.jualn.miniapp.module.audit.controller;

import cn.jualn.miniapp.common.result.Result;
import cn.jualn.miniapp.module.audit.bo.AuditMediaCheckBO;
import cn.jualn.miniapp.module.audit.bo.AuditTextCheckBO;
import cn.jualn.miniapp.module.audit.converter.AuditConverter;
import cn.jualn.miniapp.module.audit.dto.request.AuditMediaCheckRequest;
import cn.jualn.miniapp.module.audit.dto.request.AuditTextCheckRequest;
import cn.jualn.miniapp.module.audit.service.AuditService;
import cn.jualn.miniapp.module.audit.vo.AuditCheckResultVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/v1/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;
    private final AuditConverter auditConverter;

    /**
     * 文本内容安全审核接口。
     *
     * <p>接口返回时已完成微信文本审核，并同步落库审核结果。</p>
     *
     * @param request 文本审核请求，包含 targetType、targetId、content、scene、openid
     * @return 审核结果，passed 表示是否通过，traceId 可用于问题排查
     * @throws jakarta.validation.ConstraintViolationException 当请求参数未通过 Bean Validation 校验时抛出
     */
    @PostMapping("/text-check")
    public Result<AuditCheckResultVO> textCheck(@Valid @RequestBody AuditTextCheckRequest request) {
        AuditTextCheckBO textCheckBO = auditConverter.toTextCheckBO(request);
        return Result.ok(auditConverter.toCheckResultVO(
                auditService.doTextCheck(textCheckBO)
        ));
    }

    /**
     * 多媒体内容安全审核提交接口。
     *
     * <p>接口仅提交微信异步审核任务并返回 traceId，最终结果由微信回调驱动处理。</p>
     *
     * @param request 多媒体审核请求，包含 targetType、targetId、mediaUrl、mediaType、scene、openid
     * @return 审核结果，pending=true 表示已提交异步审核任务，traceId 用于回调关联
     * @throws jakarta.validation.ConstraintViolationException 当请求参数未通过 Bean Validation 校验时抛出
     */
    @PostMapping("/media-check")
    public Result<AuditCheckResultVO> mediaCheck(@Valid @RequestBody AuditMediaCheckRequest request) {
        AuditMediaCheckBO mediaCheckBO = auditConverter.toMediaCheckBO(request);
        return Result.ok(auditConverter.toCheckResultVO(
                auditService.doMediaCheck(mediaCheckBO)
        ));
    }
}
