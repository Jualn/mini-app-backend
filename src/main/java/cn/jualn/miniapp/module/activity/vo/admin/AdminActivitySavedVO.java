package cn.jualn.miniapp.module.activity.vo.admin;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class AdminActivitySavedVO {

    private String id;
    private LocalDateTime savedAt;
}
