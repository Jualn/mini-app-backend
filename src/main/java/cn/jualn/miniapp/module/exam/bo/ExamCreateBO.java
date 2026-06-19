package cn.jualn.miniapp.module.exam.bo;

import cn.jualn.miniapp.module.media.bo.AttachmentItemBO;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentSaveBO;
import cn.jualn.miniapp.module.timeline.bo.TimelineItemBO;
import cn.jualn.miniapp.module.timeline.bo.TimelineSaveBO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import cn.jualn.miniapp.module.timeline.bo.TimelineCreateBO;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 创建考试信息业务对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamCreateBO {

    private String title;
    private Integer category;
    private String content;
    private LocalDateTime registrationStart;
    private LocalDateTime registrationEnd;
    private LocalDate examDate;
    private LocalDate examDateEnd;
    private String officialUrl;

    private List<TimelineItemBO> timelineItems;
    private List<AttachmentItemBO> attachmentItems;
}

