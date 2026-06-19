package cn.jualn.miniapp.module.activity.dto.request;

import cn.jualn.miniapp.common.enums.ActivityCategory;
import cn.jualn.miniapp.module.media.dto.request.AttachmentItemRequest;
import cn.jualn.miniapp.module.timeline.dto.request.TimelineItemRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 创建活动请求参数。
 *
 * @author miniapp
 * @since 2026-04-28
 */
@Data
public class ActivityCreateRequest {

    /** 活动标题，必填 */
    @NotBlank(message = "活动标题不能为空")
    @Size(max = 128, message = "活动标题不能超过128字")
    private String title;

    /** 活动详情，必填 */
    @NotBlank(message = "活动详情不能为空")
    @Size(max = 10000, message = "活动详情不能超过10000字")
    private String content;

    /** 活动地点，可选 */
    @Size(max = 255, message = "活动地点不能超过255字")
    private String location;

    /** 活动分类：0-其他 1-文体比赛 2-志愿公益 3-思政主题 4-学术讲座 5-体育运动 */
    @NotNull(message = "活动分类不能为空")
    private ActivityCategory category;

    /** 主办/承办单位，可选 */
    @Size(max = 128, message = "主办单位不能超过128字")
    private String organizer;

    /** 参与范围位掩码，可选 */
    private Integer audienceScope;

    /** 联系人JSON，可选 */
    @Size(max = 512, message = "联系人信息不能超过512字")
    private String contactInfo;

    /** 参与方式说明，可选 */
    @Size(max = 255, message = "参与方式说明不能超过255字")
    private String joinMethod;

    /** 报名二维码URL，可选 */
    @Size(max = 512, message = "二维码URL不能超过512字")
    private String qrcodeUrl;

    /** 活动开始时间，必填 */
    @NotNull(message = "活动开始时间不能为空")
    private LocalDateTime startTime;

    /** 活动结束时间，必填 */
    @NotNull(message = "活动结束时间不能为空")
    private LocalDateTime endTime;

    /** 报名截止时间，可选 */
    private LocalDateTime enrollDeadline;

    /** 最大参与人数，可选 */
    private Integer maxParticipants;

    /** 活动封面图、附件、外链URL列表 */
    @Valid
    @Size(max = 9, message = "最多上传9个附件")
    private List<AttachmentItemRequest> attachmentItems;

    /** 活动时间线列表 */
    @Valid
    @Size(max = 20, message = "最多添加20个时间线节点")
    private List<TimelineItemRequest> timelineItems;
}
