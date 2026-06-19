package cn.jualn.miniapp.module.notify.mapper;

import cn.jualn.miniapp.module.notify.entity.Notification;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;

public interface NotificationMapper extends BaseMapper<Notification> {

    @Select("SELECT is_read FROM notification WHERE id = #{id} AND user_id = #{userId}")
    Integer selectIsReadByIdAndUserId(Long id, Long userId);
}
