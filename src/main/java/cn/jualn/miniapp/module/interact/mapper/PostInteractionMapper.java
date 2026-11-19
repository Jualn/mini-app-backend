package cn.jualn.miniapp.module.interact.mapper;

import org.apache.ibatis.annotations.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

public interface PostInteractionMapper {
    @Select("SELECT COUNT(*)>0 FROM post_view_day WHERE actor_id=#{actor} AND post_id=#{post} AND view_day=#{day}")
    boolean viewed(long actor,long post,LocalDate day);
    @Insert("INSERT INTO post_view_day(actor_id,post_id,view_day) VALUES(#{actor},#{post},#{day})")
    void view(long actor,long post,LocalDate day);
    record Share(Long postId,String action,Integer platform) { }
    @Select("SELECT post_id,action,platform FROM post_share_event WHERE actor_id=#{actor} AND event_id=#{event} FOR UPDATE")
    Share share(long actor,String event);
    @Insert("INSERT INTO post_share_event(actor_id,event_id,post_id,action,platform) VALUES(#{actor},#{event},#{post},#{action},#{platform})")
    void shareEvent(long actor,String event,long post,String action,Integer platform);
}
