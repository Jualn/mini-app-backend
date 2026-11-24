package cn.jualn.miniapp.module.post.mapper;
import org.apache.ibatis.annotations.*;
public interface MentorQuestionMapper {
    int unknownFiltered(cn.jualn.miniapp.module.post.bo.PostFeedQueryBO query);
    int unknownAnswers(long id);
    int publicAnswers(long id,String version);
    @Update("UPDATE post SET question_content_locked=TRUE WHERE id=#{id} AND question_version=#{version} AND deleted_at IS NULL AND admin_hidden=FALSE")
    int freeze(long id,String version);
    @Update("UPDATE post SET question_version=#{version} WHERE id=#{id} AND deleted_at IS NULL")
    void version(long id,String version);
}
