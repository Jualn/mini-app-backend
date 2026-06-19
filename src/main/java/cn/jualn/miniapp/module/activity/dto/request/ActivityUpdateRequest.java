package cn.jualn.miniapp.module.activity.dto.request;

import cn.jualn.miniapp.common.enums.ActivityCategory;
import cn.jualn.miniapp.module.media.dto.request.AttachmentItemRequest;
import cn.jualn.miniapp.module.timeline.dto.request.TimelineItemRequest;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 更新活动请求参数。
 *
 * @author miniapp
 * @since 2026-04-28
 */
@Data
public class ActivityUpdateRequest {

    /** 活动ID，由路径参数回填 */
    private Long id;

    /** 活动标题 */
    @Size(max = 128, message = "活动标题不能超过128字")
    private String title;

    /** 活动详情 */
    @Size(max = 5000, message = "活动详情不能超过5000字")
    private String content;

    /** 活动地点 */
    @Size(max = 255, message = "活动地点不能超过255字")
    private String location;

    /** 活动分类 */
    private ActivityCategory category;

    /** 主办/承办单位 */
    @Size(max = 128, message = "主办单位不能超过128字")
    private String organizer;

    /** 参与范围位掩码 */
    private Integer audienceScope;

    /** 联系人JSON */
    @Size(max = 512, message = "联系人信息不能超过512字")
    private String contactInfo;

    /** 参与方式说明 */
    @Size(max = 255, message = "参与方式说明不能超过255字")
    private String joinMethod;

    /** 报名二维码URL */
    @Size(max = 512, message = "二维码URL不能超过512字")
    private String qrcodeUrl;

    /** 活动开始时间 */
    private LocalDateTime startTime;

    /** 活动结束时间 */
    private LocalDateTime endTime;

    /** 报名截止时间 */
    private LocalDateTime enrollDeadline;

    /** 最大参与人数 */
    private Integer maxParticipants;

    /** 活动附件列表 */
    @Valid
    @Size(max = 20, message = "最多上传20个附件")
    private List<AttachmentItemRequest> attachmentItems;

    /** 活动时间线列表 */
    @Valid
    @Size(max = 20, message = "最多添加20个时间线节点")
    private List<TimelineItemRequest> timelineItems;
}
