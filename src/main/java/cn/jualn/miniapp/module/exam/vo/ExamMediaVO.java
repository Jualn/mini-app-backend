package cn.jualn.miniapp.module.exam.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 考试附件响应对象。
 */
@Data
@Builder
public class ExamMediaVO {

    private Integer type;
    private String url;
    private String originalName;
    private Integer sortOrder;
}

