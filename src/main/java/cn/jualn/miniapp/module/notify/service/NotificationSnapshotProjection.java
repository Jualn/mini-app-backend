package cn.jualn.miniapp.module.notify.service;

import cn.jualn.miniapp.common.enums.NotifyType;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.notify.bo.NotificationCenterBO;
import cn.jualn.miniapp.module.notify.entity.Notification;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Component
@RequiredArgsConstructor
public class NotificationSnapshotProjection {
    private final ObjectMapper objectMapper;
    private final io.micrometer.core.instrument.MeterRegistry meters;

    public NotificationCenterBO.Item item(Notification row) {
        NotificationCenterBO.Snapshot snapshot = snapshot(row);
        NotifyType known = NotifyType.fromCode(row.getType());
        String type = known == null ? "SYSTEM" : known.name();
        // Pre-snapshot change records cannot truthfully satisfy the new change schema.
        if (List.of(13,14,17,18).contains(row.getType())
                && (snapshot.presentation().changes() == null || snapshot.presentation().changes().isEmpty())) {
            type = "SYSTEM";
        }
        return new NotificationCenterBO.Item(row.getId().toString(), category(row.getType()), type,
                snapshot.presentation(), snapshot.actor(), snapshot.subject(), snapshot.target(),
                time(row.getReadAt()), time(row.getCreatedAt()));
    }
    public NotificationCenterBO.Preview preview(Notification row) {
        NotificationCenterBO.Item item = item(row);
        return new NotificationCenterBO.Preview(item.id(), item.type(), item.presentation().title(),
                item.presentation().body(), item.target(), item.createdAt());
    }
    private NotificationCenterBO.Snapshot snapshot(Notification row) {
        if (row.getContentPayload() != null) {
            try {
                JsonNode root = objectMapper.readTree(row.getContentPayload());
                if (root.hasNonNull("presentation")) {
                    return new NotificationCenterBO.Snapshot(
                            objectMapper.treeToValue(root.get("presentation"), NotificationCenterBO.Presentation.class),
                            root.hasNonNull("actor") ? objectMapper.treeToValue(root.get("actor"), NotificationCenterBO.Actor.class) : null,
                            root.hasNonNull("subject") ? objectMapper.treeToValue(root.get("subject"), NotificationCenterBO.Subject.class) : null,
                            root.hasNonNull("target") ? objectMapper.treeToValue(root.get("target"), NotificationCenterBO.Target.class) : null);
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
                meters.counter("jualn.notification.projection.failure").increment();
                throw new IllegalStateException("Invalid frozen notification presentation", failure);
            }
        }
        TargetType targetType = TargetType.fromCode(row.getTargetType());
        NotificationCenterBO.Subject subject = null;
        NotificationCenterBO.Target target = null;
        if (row.getTargetId() != null && targetType != null) {
            String id = row.getTargetId().toString();
            switch (targetType) {
                case POST -> { subject = new NotificationCenterBO.Subject("POST", id); target = post(id, null); }
                case ACTIVITY -> { subject = new NotificationCenterBO.Subject("ACTIVITY", id); target = new NotificationCenterBO.Target("ACTIVITY_DETAIL", null, null, id, null); }
                case EXAM -> { subject = new NotificationCenterBO.Subject("PUBLIC_EVENT", id); target = new NotificationCenterBO.Target("PUBLIC_EVENT_DETAIL", null, null, null, id); }
                case COMMENT -> subject = new NotificationCenterBO.Subject("COMMENT", id);
                default -> { }
            }
        }
        String title = row.getTitle() == null || row.getTitle().isBlank() ? "通知" : row.getTitle();
        return new NotificationCenterBO.Snapshot(new NotificationCenterBO.Presentation(title, row.getContent(), null, null, null),
                null, subject, target);
    }
    public static NotificationCenterBO.Target post(String id, String commentId) {
        return new NotificationCenterBO.Target("POST_DETAIL", id, commentId, null, null);
    }
    public static String category(Integer code) {
        if (code != null && (code == 1 || code == 2 || code == 3 || code >= 19 && code <= 22)) { return "INTERACTION"; }
        if (code != null && (code == 4 || code == 5 || code >= 8 && code <= 18)) { return "ACTIVITY"; }
        return "SYSTEM";
    }
    private String time(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.of("Asia/Shanghai")).toOffsetDateTime().toString();
    }
}
