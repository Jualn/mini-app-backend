package cn.jualn.miniapp.module.audit.mapper;
import cn.jualn.miniapp.module.audit.entity.ContentSafetyReview;
import cn.jualn.miniapp.module.audit.entity.ContentSafetyCheck;
import org.apache.ibatis.annotations.*;
import java.util.List;
public interface ContentSafetyReviewMapper {
    @Update("UPDATE content_safety_review SET state='invalidated',invalidation_reason=#{reason} WHERE target_type=#{type} AND target_id=#{id} AND state IN ('checking','check-failed','pending')")
    void invalidateTarget(@Param("type") int type,@Param("id") long id,@Param("reason") String reason);
    @Update("UPDATE content_safety_review r JOIN comment c ON r.target_type=4 AND r.target_id=c.id SET r.state='invalidated',r.invalidation_reason='subject-unavailable' WHERE c.target_type=#{type} AND c.target_id=#{id} AND r.state IN ('checking','check-failed','pending')")
    void invalidateSubject(@Param("type") int type,@Param("id") long id);
    @Update("UPDATE content_safety_review r JOIN comment c ON r.target_type=4 AND r.target_id=c.id SET r.state='invalidated',r.invalidation_reason='subject-unavailable' WHERE c.parent_id=#{id} AND NOT (c.community_thread=TRUE AND c.target_type=1 AND EXISTS(SELECT 1 FROM post p WHERE p.id=c.target_id AND p.post_type IN('NORMAL','MENTOR_QA'))) AND r.state IN ('checking','check-failed','pending')")
    void invalidateRoot(long id);
    @Select("WITH RECURSIVE attempts AS (SELECT audit_log_id FROM content_safety_check WHERE audit_log_id=#{id} UNION ALL SELECT c.audit_log_id FROM content_safety_check c JOIN attempts p ON c.supersedes_check_id=p.audit_log_id) SELECT c.*,l.wx_trace_id FROM attempts a JOIN content_safety_check c ON c.audit_log_id=a.audit_log_id JOIN content_audit_log l ON l.id=c.audit_log_id ORDER BY c.audit_log_id DESC LIMIT 1")
    ContentSafetyCheck currentAttempt(long id);
    @Update("UPDATE content_safety_check SET execution_state='failed',result='unknown' WHERE audit_log_id=#{id} AND execution_state IN ('queued','running')") int failed(long id);
    @Select("SELECT c.*,l.wx_trace_id FROM content_safety_check c JOIN content_audit_log l ON l.id=c.audit_log_id WHERE c.audit_log_id=#{id}") ContentSafetyCheck check(long id);
    @Update("UPDATE content_safety_check SET execution_state='running' WHERE audit_log_id=#{id} AND execution_state='queued'") int begin(long id);
    @Update("UPDATE content_safety_check SET execution_state='unknown',result='unknown' WHERE audit_log_id=#{id} AND execution_state='running'") int unknown(long id);
    @Select("SELECT id,title,content,question_version,post_type,revision,audit_revision,admin_hidden,community_version,audit_status,temporary_visible_until,status,deleted_at FROM post WHERE id=#{id}") ReviewSubjectRows.Post post(long id);
    @Select("SELECT id,question_version,target_type,target_id,parent_id,reply_to_comment_id,community_thread,content,image_url,status,deleted_at FROM comment WHERE id=#{id}") ReviewSubjectRows.Comment comment(long id);
    @Select("SELECT publish_status FROM activity WHERE id=#{id} AND deleted_at IS NULL") String activity(long id);
    @Select("SELECT publish_status FROM public_event WHERE id=#{id} AND deleted_at IS NULL") String event(long id);
    @Select("SELECT id,title,content,question_version,post_type,revision,audit_revision,admin_hidden,community_version,audit_status,temporary_visible_until,status,deleted_at FROM post WHERE id=#{id} FOR UPDATE") ReviewSubjectRows.Post lockPost(long id);
    @Select("SELECT id,question_version,target_type,target_id,parent_id,reply_to_comment_id,community_thread,content,image_url,status,deleted_at FROM comment WHERE id=#{id} FOR UPDATE") ReviewSubjectRows.Comment lockComment(long id);
    @Select("SELECT publish_status FROM activity WHERE id=#{id} AND deleted_at IS NULL FOR UPDATE") String lockActivity(long id);
    @Select("SELECT publish_status FROM public_event WHERE id=#{id} AND deleted_at IS NULL FOR UPDATE") String lockEvent(long id);
    @Select("SELECT * FROM content_safety_review WHERE task_id=#{id}") ContentSafetyReview find(long id);
    @Select("SELECT * FROM content_safety_review WHERE task_id=#{id} FOR UPDATE") ContentSafetyReview lock(long id);
    @Select("SELECT r.* FROM content_safety_review r JOIN content_safety_check c ON c.task_id=r.task_id WHERE c.audit_log_id=#{id}") ContentSafetyReview forCheck(long id);
    @Select("SELECT * FROM content_safety_review WHERE target_type=#{type} AND target_id=#{id} ORDER BY task_id DESC LIMIT 1 FOR UPDATE")
    ContentSafetyReview latest(@Param("type") int type,@Param("id") long id);
    @Insert("INSERT INTO content_safety_review(task_id,target_type,target_id,submission_version,scene,resource_namespace,business_revision,snapshot_title,snapshot_content,snapshot_attachments,snapshot_qa_relation,growth_organization_id,expected_checks,state,previous_task_id,created_at) VALUES(#{taskId},#{targetType},#{targetId},#{submissionVersion},#{scene},#{resourceNamespace},#{businessRevision},#{snapshotTitle},#{snapshotContent},#{snapshotAttachments},#{snapshotQaRelation},#{growthOrganizationId},#{expectedChecks},#{state},#{previousTaskId},#{createdAt})")
    void insert(ContentSafetyReview round);
    @Insert("INSERT INTO content_safety_check(audit_log_id,task_id,input_reference,supersedes_check_id) VALUES(#{auditLogId},#{taskId},#{inputReference},#{supersedesCheckId})") void insertCheck(ContentSafetyCheck check);
    @Select("SELECT c.*,l.wx_trace_id FROM content_safety_check c JOIN content_audit_log l ON l.id=c.audit_log_id WHERE c.task_id=#{id} ORDER BY c.audit_log_id") List<ContentSafetyCheck> checks(long id);
    @Update("UPDATE content_safety_review SET state=#{state},invalidation_reason=#{invalidationReason},decision_origin=#{decisionOrigin},decided_at=#{decidedAt},superseded_by_task_id=#{supersededByTaskId} WHERE task_id=#{taskId}") void save(ContentSafetyReview round);
    @Update("UPDATE content_audit_log SET review_task_id=#{round} WHERE id=#{log}") void link(@Param("log") long log,@Param("round") long round);
    @Update("UPDATE content_safety_check SET execution_state=#{execution},result=#{result},provider_facts=#{facts} WHERE audit_log_id=#{id} AND execution_state IN ('queued','running','unknown','failed')")
    int complete(@Param("id") long id,@Param("execution") String execution,@Param("result") String result,@Param("facts") String facts);
    @Select("SELECT r.* FROM content_safety_review r WHERE r.state IN ('checking','check-failed') AND EXISTS(SELECT 1 FROM content_safety_check c WHERE c.task_id=r.task_id AND c.execution_state='running') ORDER BY r.task_id LIMIT 100") List<ContentSafetyReview> waiting();
}
