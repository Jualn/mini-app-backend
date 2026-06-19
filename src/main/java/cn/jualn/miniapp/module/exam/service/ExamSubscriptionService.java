package cn.jualn.miniapp.module.exam.service;

/**
 * 考试订阅业务接口。
 */
public interface ExamSubscriptionService {

    /**
     * 订阅考试信息。
     *
     * @param examId 考试ID
     */
    void subscribeExam(Long examId);

    /**
     * 取消订阅考试信息。
     *
     * @param examId 考试ID
     */
    void unsubscribeExam(Long examId);

    boolean isSubscribed(Long examId);

    /**
     * 分页获取指定考试的订阅用户 ID 列表（用于广播）。
     *
     * @param examId 考试 ID
     * @param lastId 游标
     * @param limit  分页大小
     * @return 用户 ID 列表
     */
    java.util.List<Long> listSubscriberUserIds(Long examId, long lastId, int limit);
}


