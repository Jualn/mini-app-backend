package cn.jualn.miniapp.module.setting.mapper;

import cn.jualn.miniapp.module.setting.entity.UserSetting;
import cn.jualn.miniapp.module.setting.vo.UserSettingVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;

public interface UserSettingMapper extends BaseMapper<UserSetting> {
    @Select("SELECT * FROM user_setting WHERE user_id=#{userId} FOR UPDATE")
    UserSetting selectByIdForUpdate(Long userId);
}
