package cn.jualn.miniapp.module.learningtask.service;

import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO;
import cn.jualn.miniapp.module.learningtask.converter.LearningTaskSliceConverter;
import cn.jualn.miniapp.module.learningtask.mapper.LearningTaskSliceMapper;
import cn.jualn.miniapp.module.learningtask.vo.admin.LearningTaskSliceVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class LearningTaskSubmissionQueries {
    private final LearningTaskService tasks;
    private final LearningTaskProviders providers;
    private final LearningTaskSliceMapper mapper;
    private final LearningTaskSliceQueries own;
    private final LearningTaskSliceConverter converter;
    public LearningTaskSubmissionQueries(LearningTaskService tasks,LearningTaskProviders providers,LearningTaskSliceMapper mapper,
            LearningTaskSliceQueries own,LearningTaskSliceConverter converter) {
        this.tasks=tasks;this.providers=providers;this.mapper=mapper;this.own=own;this.converter=converter;
    }
    public LearningTaskSliceVO.SubmissionPage history(long assignment,int page,int size) {
        long task=own.ownTask(assignment);
        tasks.mine(task,assignment);
        var items=mapper.submissionHistory(assignment,((long)page-1)*size,size).stream()
                .map(id->converter.submission(tasks.ownSubmission(task,assignment,id).submission())).toList();
        return new LearningTaskSliceVO.SubmissionPage(items,page,size,mapper.submissionHistoryCount(assignment));
    }
    public LearningTaskSliceVO.ReviewPage queue(int page,int size,boolean actionable,Long task,String status) {
        providers.authorize(LearningTaskBO.Action.REVIEW_READ,task,null);
        var scope=providers.reviewableTasks();
        var items=mapper.reviewQueue(scope,actionable,task,status,((long)page-1)*size,size).stream()
                .map(tasks::adminSubmission)
                .filter(r->!actionable || r.task().status()==LearningTaskBO.Status.PUBLISHED
                        && r.assignment().allocationStatus()==LearningTaskBO.Allocation.ACTIVE
                        && r.assignment().progress()==LearningTaskBO.Progress.PENDING_REVIEW
                        && java.util.Objects.equals(r.assignment().latestSubmissionId(),r.submission().submissionId())
                        && r.submission().reviewStatus()==LearningTaskBO.ReviewStatus.PENDING_REVIEW)
                .map(this::review).toList();
        return new LearningTaskSliceVO.ReviewPage(items,page,size,mapper.reviewQueueCount(scope,actionable,task,status));
    }
    @org.springframework.beans.factory.annotation.Autowired
    private LearningTaskSubmitterSummaryReader summaries;
    public record ReviewRead(LearningTaskBO.Result facts,LearningTaskSliceVO.ReviewDetail view) {}
    public ReviewRead detail(long id) {var facts=tasks.adminSubmission(id);return new ReviewRead(facts,review(facts));}
    private LearningTaskSliceVO.ReviewDetail review(LearningTaskBO.Result result) {
        var view=converter.review(result);
        return new LearningTaskSliceVO.ReviewDetail(view.task(),view.assignment(),view.submission(),summaries.read(result.submission()));
    }
}
