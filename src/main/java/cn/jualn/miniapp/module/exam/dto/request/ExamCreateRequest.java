package cn.jualn.miniapp.module.exam.dto.request;

import cn.jualn.miniapp.module.media.dto.request.AttachmentItemRequest;
import cn.jualn.miniapp.module.timeline.dto.request.TimelineItemRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 创建考试信息请求参数。
 */
@Data
public class ExamCreateRequest {

    /** 考试标题 */
    @NotBlank(message = "考试标题不能为空")
    @Size(max = 128, message = "考试标题不能超过128字")
    private String title;

    /** 考试分类：0-其他 1-英语类 2-普通话 3-计算机 4-考研 5-考公 6-教师资格 7-职业资格 */
    @NotNull(message = "考试分类不能为空")
    private Integer category;

    /** 考试详情 */
    @NotBlank(message = "考试详情不能为空")
    @Size(max = 5000, message = "考试详情不能超过5000字")
    private String content;

    /** 报名开始时间 */
    private LocalDateTime registrationStart;

    /** 报名截止时间 */
    private LocalDateTime registrationEnd;

    /** 考试日期 */
    private LocalDate examDate;

    /** 考试结束日期 */
    private LocalDate examDateEnd;

    /** 官方报名/信息链接 */
    @Size(max = 512, message = "官方链接不能超过512字")
    private String officialUrl;

    /** 附件列表 */
    @Valid
    @Size(max = 9, message = "最多上传9个附件")
    private List<AttachmentItemRequest> attachmentItems;

    /** 时间线列表 */
    @Valid
    @Size(max = 20, message = "最多添加20个时间线节点")
    private List<TimelineItemRequest> timelineItems;
}

