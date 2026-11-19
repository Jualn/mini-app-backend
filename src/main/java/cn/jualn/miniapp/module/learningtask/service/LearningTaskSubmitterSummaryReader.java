package cn.jualn.miniapp.module.learningtask.service;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.*;
import cn.jualn.miniapp.module.person.service.profile.SubmissionNameReader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
/** Called after the task owner authorizes this fixed submission, without granting general person-directory access. */
@Service @RequiredArgsConstructor
public class LearningTaskSubmitterSummaryReader {
    private final SubmissionNameReader names;
    public SubmitterSummary read(Submission submission) {
        String name=null;
        if(submission.subject()!=null && "PERSON".equals(submission.subject().type()))
            name=names.name(submission.subject().personId(),submission.bindingId(),submission.submittedByUserId(),
                submission.submittedAt().atZone(java.time.ZoneId.of("Asia/Shanghai")).withZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime());
        return name==null || name.isBlank()?new SubmitterSummary("UNAVAILABLE",null):new SubmitterSummary("AVAILABLE",name);
    }
}
