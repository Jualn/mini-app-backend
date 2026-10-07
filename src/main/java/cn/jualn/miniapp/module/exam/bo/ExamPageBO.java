package cn.jualn.miniapp.module.exam.bo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 考试分页业务对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamPageBO {

    private String cursor;
    private Long lastId;
    private Integer pageSize;
    private Integer category;
    private Integer status;
    private Integer eventType;
    private Integer lifecycleStatus;
    private String keyword;
}

