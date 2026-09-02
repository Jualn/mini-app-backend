package cn.jualn.miniapp.module.audit.vo.admin;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class AdminReviewItemVO {
    private String taskId;
    private String targetId;
    private String targetType;
    private String title;
    private String contentPreview;
    private Author author;
    private String riskLevel;
    private Integer riskScore;
    private String machineConclusion;
    private List<String> matchedLabels;
    private String sourceType;
    private LocalDateTime submittedAt;
    private Long waitingMinutes;
    private Long reportCount;
    private Long attachmentCount;
    private String status;

    @Data
    public static class Author {
        private String id;
        private String nickname;
        private String avatarUrl;
        private String roleCode;
        private String accountStatus;
        private LocalDateTime joinedAt;
    }
}
