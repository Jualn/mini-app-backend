package cn.jualn.miniapp.module.learningtask.mapper;

import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Notice;
import org.apache.ibatis.annotations.*;

@Mapper
public interface LearningTaskNoticeMapper {
    @Insert("INSERT INTO learning_task_notice(occurrence,task_id,assignment_id,type,subject_type,person_id,subject_user_id,delivery_user_id,binding_id,availability,created_at) VALUES(#{occurrence},#{taskId},#{assignmentId},#{type},#{subject.type},#{subject.personId},#{subject.userId},#{userId},#{bindingId},#{availability},CONVERT_TZ(UTC_TIMESTAMP(6),'+00:00','+08:00')) ON DUPLICATE KEY UPDATE occurrence=occurrence")
    int retain(Notice notice);
}
