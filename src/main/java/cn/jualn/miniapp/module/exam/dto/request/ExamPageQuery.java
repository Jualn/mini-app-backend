package cn.jualn.miniapp.module.exam.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 考试信息分页查询参数。
 */
@Data
public class ExamPageQuery {

    /** 上一页最后一条ID（游标分页），首次传 null */
    private Long lastId;

    /** 每页大小 */
    @Min(value = 1, message = "页面大小最小为1")
    @Max(value = 50, message = "每页最多50条")
    private Integer pageSize = 20;

    /** 考试分类筛选 */
    private Integer category;

    /** 考试状态筛选（默认仅展示已发布） */
    private Integer status;

    /** 关键词 */
    private String keyword;
}

