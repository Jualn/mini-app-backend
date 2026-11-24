package cn.jualn.miniapp.module.learningtask.converter;

import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO;
import cn.jualn.miniapp.module.learningtask.vo.admin.LearningTaskSliceVO;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

@Component
public class LearningTaskSliceConverter {
    public LearningTaskSliceVO.Submission submission(LearningTaskBO.Submission s) {
        return new LearningTaskSliceVO.Submission(id(s.submissionId()),id(s.assignmentId()),s.attempt(),"MATERIAL",s.text(),
                s.fileIds().isEmpty()?null:s.fileIds().stream().map(this::id).toList(),time(s.confirmedAt()),time(s.submittedAt()),
                s.reviewStatus().name(),time(s.reviewedAt()),nullable(s.reviewedBy()),s.reviewReason(),id(s.submittedByUserId()),s.subject(),s.bindingId());
    }
    public LearningTaskSliceVO.ReviewDetail review(LearningTaskBO.Result result) {
        var detail=mine(result);
        return new LearningTaskSliceVO.ReviewDetail(detail.task(),detail.assignment(),submission(result.submission()));
    }
    private final LearningTaskDraftConverter drafts;
    public LearningTaskSliceConverter(LearningTaskDraftConverter drafts) { this.drafts=drafts; }
    public LearningTaskSliceVO.Preview preview(LearningTaskBO.Preview p) {
        return new LearningTaskSliceVO.Preview(id(p.previewId()),id(p.taskId()),p.purpose().name(),p.members().size(),
                p.newAssignmentCount(),p.alreadyAssignedCount(),time(p.expiresAt()),time(p.createdAt()),p.canApply(),p.blockedCount());
    }
    public LearningTaskSliceVO.MyDetail mine(LearningTaskBO.Result r) {
        var t=drafts.detail(r.task());
        return new LearningTaskSliceVO.MyDetail(new LearningTaskSliceVO.Task(t.taskId(),t.title(),t.description(),t.status(),t.article(),
                t.requirements(),t.reviewRequired(),t.firstDeadline(),t.deadlineRevision(),t.createdAt(),t.updatedAt(),t.createdBy(),
                t.publishedAt(),t.closedAt(),t.cancelledAt(),t.cancelledBy(),t.cancellationReason(),t.managementTarget()),assignment(r.assignment()));
    }
    public LearningTaskSliceVO.Assignment assignment(LearningTaskBO.Assignment a) {
        return new LearningTaskSliceVO.Assignment(id(a.assignmentId()),id(a.taskId()),a.subject(),a.allocationStatus().name(),a.progress().name(),
                a.displayStatus(),time(a.assignedAt()),id(a.assignedBy()),time(a.confirmedAt()),time(a.firstSubmittedAt()),a.onTimeFirstSubmission(),
                nullable(a.latestSubmissionId()),time(a.supplementDeadline()),time(a.completedAt()),time(a.revokedAt()),nullable(a.revokedBy()),a.revocationReason(),nullable(a.confirmedByUserId()),a.confirmedBindingId());
    }
    public String time(LocalDateTime value) { return value==null?null:value.atZone(ZoneId.of("Asia/Shanghai")).format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME); }
    private String id(long value) { return Long.toString(value); }
    private String nullable(Long value) { return value==null?null:value.toString(); }
}
