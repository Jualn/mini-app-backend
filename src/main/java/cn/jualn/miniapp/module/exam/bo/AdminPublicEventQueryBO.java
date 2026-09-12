package cn.jualn.miniapp.module.exam.bo;
import lombok.Data;
import jakarta.validation.constraints.*;
@Data
public class AdminPublicEventQueryBO {
    private String keyword;
    private Integer category;
    private Integer eventType;
    private Integer publishStatus;
    private String cursor;
    private Integer pageSize = 20;
}
