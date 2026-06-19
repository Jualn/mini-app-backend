package cn.jualn.miniapp.module.activity.dto.request;

import cn.jualn.miniapp.common.enums.ActivityCategory;
import cn.jualn.miniapp.common.enums.ActivityStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 活动分页查询参数。
 *
 * @author miniapp
 * @since 2026-04-28
 */
@Data
public class ActivityPageQuery {

    /** 上一页最后一条ID（游标分页），首次传 null */
    private Long lastId;

    /** 每页大小 */
    @Min(value = 1, message = "页面大小最小为1")
    @Max(value = 50, message = "每页最多50条")
    private Integer pageSize = 20;

    /** 活动分类筛选，可选：0-其他 1-文体比赛 2-志愿公益 3-思政主题 4-学术讲座 5-体育运动 */
    private ActivityCategory category;

    /** 活动状态筛选，仅管理员可用 */
    private ActivityStatus status;

    /** 搜索关键词 */
    private String keyword;
}
