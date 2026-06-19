package cn.jualn.miniapp.module.exam.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("exam_info")
public class ExamInfo {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String title;

    private Integer category;

    private String content;

    private LocalDateTime registrationStart;

    private LocalDateTime registrationEnd;

    private LocalDate examDate;

    private LocalDate examDateEnd;

    private String officialUrl;

    private Integer status;

    private Integer auditStatus;

    private String rejectReason;

    private Boolean isPinned;

    private Integer commentCount;

    private Integer likeCount;

    private Integer viewCount;

    private LocalDateTime publishedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private LocalDateTime deletedAt;

}
