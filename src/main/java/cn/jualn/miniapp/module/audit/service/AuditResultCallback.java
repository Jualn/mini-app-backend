package cn.jualn.miniapp.module.audit.service;

/**
 * 审核结果业务回调接口。
 *
 * <p>实现类应带 @AuditTarget 注解指定所转租的审核目标类型，审核业务流丫中会获取并调用对应实现。</p>
 */
public interface AuditResultCallback {

    /**
     * 审核通过时的业务回调。
     *
     * @param targetId 审核的目标 ID
     */
    void onPass(Long targetId);

    /**
     * 审核拒绝时的业务回调。
     *
     * @param targetId 审核的目标 ID
     * @param reason 拒绝原因，由审核服务氇锛，需要映射到业务侧的具体错注信息
     */
    void onReject(Long targetId, String reason);
}

// 实现的类名必须以 XxxResultCallback 结尾, xxx必须是业务使用的 targetType 的首字母大写
// 例如: PostResultCallback, ActivityResultCallback, ExamResultCallback, CommentResultCallback
