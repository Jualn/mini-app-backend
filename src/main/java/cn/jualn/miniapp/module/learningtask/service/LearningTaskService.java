package cn.jualn.miniapp.module.learningtask.service;

import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Apply;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Deadline;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Edit;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Material;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.PreviewRequest;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Purpose;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Result;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Review;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Revoke;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Save;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Submission;

import java.util.List;

import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.AdminPage;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.AdminQuery;

public interface LearningTaskService {
    Result create(Save command, String key);

    Result get(long taskId);

    AdminPage listAdmin(AdminQuery query);

    Result edit(long taskId, Edit command, String ifMatch);

    Result preview(long taskId, PreviewRequest command, String ifMatch, String key);

    Result preview(long taskId, long previewId);

    cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.PreviewMemberPage previewMembers(long taskId, long previewId, int page, int pageSize);

    Result apply(long taskId, Apply command, Purpose purpose, String ifMatch, String key);

    Result deadline(long taskId, Deadline command, String ifMatch, String key);

    Result cancel(long taskId, String reason, String ifMatch, String key);

    Result close(long taskId, String ifMatch, String key);

    Result revoke(long taskId, long assignmentId, Revoke command, String ifMatch, String key);

    Result mine(long taskId, long assignmentId);

    Result adminAssignment(long taskId, long assignmentId);

    Result confirm(long taskId, long assignmentId, String ifMatch, String key);

    Result submit(long taskId, long assignmentId, Material command, String ifMatch, String key);

    Result review(long taskId, long assignmentId, long submissionId, Review command, String ifMatch, String key);

    List<Submission> history(long taskId, long assignmentId);

    Result ownSubmission(long taskId, long assignmentId, long submissionId);

    Result adminSubmission(long submissionId);
}
