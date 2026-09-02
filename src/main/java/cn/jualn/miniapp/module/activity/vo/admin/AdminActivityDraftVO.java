package cn.jualn.miniapp.module.activity.vo.admin;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class AdminActivityDraftVO {

    private String id;
    private String title;
    private String category;
    private String organizer;
    private String content;
    private String location;
    private List<String> audienceCodes;
    private String contactName;
    private String contactPhone;
    private String joinMethod;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime enrollDeadline;
    private String maxParticipants;
    private List<TimelineItem> timeline;
    private List<Attachment> attachments;

    @Data
    @Builder
    public static class TimelineItem {
        private String id;
        private String label;
        private String description;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
    }

    @Data
    @Builder
    public static class Attachment {
        private String id;
        private String type;
        private String objectKey;
        private String name;
        private String detail;
    }
}
