package cn.jualn.miniapp.module.admin.operation.bo;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;
public final class AdminAuditBO {
    private AdminAuditBO() {}
    @JsonInclude(JsonInclude.Include.NON_NULL) public record Actor(String type,String userId) {}
    public record Reference(String kind,String id) {}
    public record Coverage(String source,String status,String availableSince) {}
    @JsonInclude(JsonInclude.Include.NON_NULL) public record Page(List<JsonNode> items,String nextCursor,List<Coverage> coverage) {}
    public record Query(String kind,long actor,LocalDateTime after,LocalDateTime before,String person,String user,String source,
        String actorUser,String operation,String targetType,String targetId,String result,int size) {}
    @Data public static class Row {
        private long sequence; private String id; private String kind; private String source; private String operation;
        private String actorUser; private String targetUser; private String targetPerson; private String targetType;
        private String targetId; private String result; private LocalDateTime recordedAt; private String payload;
    }
}
