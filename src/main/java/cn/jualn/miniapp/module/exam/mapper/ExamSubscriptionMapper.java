package cn.jualn.miniapp.module.exam.mapper;

import cn.jualn.miniapp.module.exam.entity.ExamSubscription;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 考试订阅数据访问层。
 *
 * <p>负责考试订阅相关查询，避免业务层拼装SQL。</p>
 *
 * @author miniapp
 * @since 2026-04-28
 */
@Mapper
public interface ExamSubscriptionMapper extends BaseMapper<ExamSubscription> {

    /**
     * 查询用户对考试的最新订阅记录。
     *
     * @param examId 考试ID
     * @param userId 用户ID
     * @return 订阅记录，不存在返回null
     */
    @Select("SELECT * FROM exam_subscription WHERE exam_info_id = #{examId} AND user_id = #{userId} ORDER BY created_at DESC LIMIT 1")
    ExamSubscription selectLatestByExamAndUser(@Param("examId") Long examId, @Param("userId") Long userId);

    /**
     * 查询用户对考试的有效订阅记录。
     *
     * @param examId 考试ID
     * @param userId 用户ID
     * @param status 订阅状态
     * @return 订阅记录，不存在返回null
     */
    @Select("SELECT * FROM exam_subscription WHERE exam_info_id = #{examId} AND user_id = #{userId} AND status = #{status} ORDER BY created_at DESC LIMIT 1")
    ExamSubscription selectActiveByExamAndUser(@Param("examId") Long examId,
					       @Param("userId") Long userId,
					       @Param("status") Integer status);

    /**
     * 查询用户对多个考试的订阅记录。
     *
     * @param userId 用户ID
     * @param examIds 考试ID集合
     * @param status 订阅状态
     * @return 订阅记录列表
     */
    @Select({
	    "<script>",
	    "SELECT * FROM exam_subscription",
	    "WHERE user_id = #{userId}",
	    "AND status = #{status}",
	    "AND exam_info_id IN",
	    "<foreach collection='examIds' item='examId' open='(' separator=',' close=')'>",
	    "#{examId}",
	    "</foreach>",
	    "</script>"
    })
    List<ExamSubscription> selectActiveByUserAndExamIds(@Param("userId") Long userId,
							@Param("examIds") List<Long> examIds,
							@Param("status") Integer status);

    @Select("SELECT user_id FROM exam_subscription WHERE exam_info_id=#{examId} AND status=1 " +
            "AND notify_enable=1 AND user_id &gt; #{lastUserId} AND user_id &lt;= #{upperUserId} " +
            "ORDER BY user_id LIMIT #{limit}")
    List<Long> selectSubscriberUserIdsBounded(@Param("examId") Long examId,
                                               @Param("lastUserId") long lastUserId,
                                               @Param("upperUserId") long upperUserId,
                                               @Param("limit") int limit);

    @Select("SELECT COALESCE(MAX(user_id),0) FROM exam_subscription " +
            "WHERE exam_info_id=#{examId} AND status=1 AND notify_enable=1")
    long selectSubscriberUpperBound(@Param("examId") Long examId);
}
