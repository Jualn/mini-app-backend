package cn.jualn.miniapp.module.publicevent.bo;
import lombok.Data;
import java.time.LocalDateTime;
@Data public class AdminPublicEventListBO {
    private Long id;
    private String title;
    private Integer publishStatus;
    private Integer lifecycleStatus;
    private LocalDateTime updatedAt;
}
