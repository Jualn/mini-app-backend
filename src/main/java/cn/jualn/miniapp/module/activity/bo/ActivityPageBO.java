package cn.jualn.miniapp.module.activity.bo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 活动分页查询业务对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivityPageBO {

    /** Canonical opaque cursor. Legacy internal callers may continue to use lastId. */
    private String cursor;
    private Long lastId;
    private Integer pageSize;
    private Integer category;
    private Integer status;
    private Integer lifecycleStatus;
    /** Unknown department identity may discover campus-wide activities only. */
    private boolean campusAudienceOnly;
    /**
     * 关键词，模糊匹配活动标题或组织者名称。
     */
    private String keyword;
}
