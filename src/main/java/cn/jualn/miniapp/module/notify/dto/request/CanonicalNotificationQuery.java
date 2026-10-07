package cn.jualn.miniapp.module.notify.dto.request;

import cn.jualn.miniapp.module.notify.model.NotificationCategory;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class CanonicalNotificationQuery {
    private String cursor;
    @Min(1)
    @Max(50)
    private Integer pageSize = 20;
    private NotificationCategory category;
    private Boolean isRead;
    @Pattern(regexp = "legacy|structured")
    private String representation = "legacy";
    @Pattern(regexp = "INTERACTION|ACTIVITY|SYSTEM")
    private String boxCategory;
}
