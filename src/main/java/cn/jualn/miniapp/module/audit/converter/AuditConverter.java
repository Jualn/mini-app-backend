package cn.jualn.miniapp.module.audit.converter;

import cn.jualn.miniapp.common.enums.MediaType;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.audit.bo.AuditCheckResultBO;
import cn.jualn.miniapp.module.audit.bo.AuditMediaCheckBO;
import cn.jualn.miniapp.module.audit.bo.AuditTextCheckBO;
import cn.jualn.miniapp.module.audit.dto.inner.AuditCheckResultDTO;
import cn.jualn.miniapp.module.audit.dto.inner.AuditMediaCheckDTO;
import cn.jualn.miniapp.module.audit.dto.inner.AuditTextCheckDTO;
import cn.jualn.miniapp.module.audit.dto.request.AuditMediaCheckRequest;
import cn.jualn.miniapp.module.audit.dto.request.AuditTextCheckRequest;
import cn.jualn.miniapp.module.audit.payload.AuditMediaPayload;
import cn.jualn.miniapp.module.audit.payload.AuditTextPayload;
import cn.jualn.miniapp.module.audit.vo.AuditCheckResultVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * 审核数据转换器（MapStruct）。
 *
 * <p>负责在 Request、BO、DTO、VO、Payload 等多层对象之间的转换，使用 MapStruct 自动生成实现。
 * 支持同一个源类型到多个目标类型的转换（重载方法）。</p>
 */
@Mapper(componentModel = "spring")
public interface AuditConverter {

    /**
     * 将文本审核请求转换为 BO 对象。
     * @param request 前端 API 请求
     * @return 业务对象
     */
    @Mapping(target = "auditLogId", ignore = true)
    AuditTextCheckBO toTextCheckBO(AuditTextCheckRequest request);

    /**
     * 将文本审核 Payload 转换为 BO 对象。
     * @param dto 队列消息 payload
     * @return 业务对象
     */
    AuditTextCheckBO toTextCheckBO(AuditTextPayload dto);

    /**
     * 将多媒体审核请求转换为 BO 对象。
     * @param request 前端 API 请求
     * @return 业务对象
     */
    @Mapping(target = "auditLogId", ignore = true)
    AuditMediaCheckBO toMediaCheckBO(AuditMediaCheckRequest request);

    /**
     * 将多媒体审核 Payload 转换为 BO 对象。
     * @param payload 队列消息 payload
     * @return 业务对象
     */
    AuditMediaCheckBO toMediaCheckBO(AuditMediaPayload payload);

    /**
     * 将审核结果 BO 转换为前端 VO 对象。
     * @param bo 业务对象
     * @return 前端展示对象
     */
    AuditCheckResultVO toCheckResultVO(AuditCheckResultBO bo);

    default Integer resolveMediaTypeCode(MediaType mediaType) {
        return mediaType.getCode();
    }

    default MediaType resolveMediaType(Integer mediaTypeCode){
        return MediaType.fromCode(mediaTypeCode);
    }

    default Integer resolveTargetTypeCode(TargetType targetType) {
        return targetType.getCode();
    }

    default TargetType resolveTargetType(Integer targetTypeCode) {
        return TargetType.fromCode(targetTypeCode);
    }
}
