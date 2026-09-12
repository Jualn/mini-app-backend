package cn.jualn.miniapp.module.activity.vo.admin;
import lombok.Data;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.List;
@Data
public class AdminActivityRegistrationVO {
    private Long id;
    private Long activityId;
    private Long userId;
    private Integer status;
    private LocalDateTime submittedAt;
    private LocalDateTime cancelledAt;
    private LocalDateTime invalidatedAt;
    private Long invalidatedBy;
    private String invalidReason;
    private JsonNode formData;
    private List<Answer> answers;
    public record Answer(String key, String label, String type, String displayValue) {}
}
