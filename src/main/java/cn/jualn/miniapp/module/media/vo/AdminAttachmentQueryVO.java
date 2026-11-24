package cn.jualn.miniapp.module.media.vo;

import cn.jualn.miniapp.common.web.AdminQuery;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

public final class AdminAttachmentQueryVO {
    private AdminAttachmentQueryVO() {}
    public record Batch(Instant evaluatedAt, List<Item> items) {}
    public record Projection(List<Item> items) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(String attachmentId, String status, AttachmentVO attachment, AdminQuery.Problem problem) {}
}
