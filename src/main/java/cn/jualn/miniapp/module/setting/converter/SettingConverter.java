package cn.jualn.miniapp.module.setting.converter;

import cn.jualn.miniapp.module.setting.bo.UserSettingBO;
import cn.jualn.miniapp.module.setting.dto.request.UserSettingUpdateRequest;
import cn.jualn.miniapp.module.setting.entity.UserSetting;
import cn.jualn.miniapp.module.setting.vo.UserSettingVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface SettingConverter {

    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "privacyShowLikes", ignore = true)
    @Mapping(target = "privacyAllowFollow", ignore = true)
    @Mapping(target = "privacyAllowMessage", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    UserSetting toEntity(UserSettingBO bo);

    UserSettingVO toVO(UserSettingBO bo);

    UserSettingBO toBO(UserSetting userSetting);

    UserSettingBO toBO(UserSettingUpdateRequest request);
}
