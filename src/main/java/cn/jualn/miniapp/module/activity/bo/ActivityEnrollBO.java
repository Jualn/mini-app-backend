package cn.jualn.miniapp.module.activity.bo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 活动订阅业务对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivityEnrollBO {

    private Long activityId;
    private Integer type;
    private Integer notifyEnable;
}