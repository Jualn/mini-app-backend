package cn.jualn.miniapp.module.activity.converter;
import cn.jualn.miniapp.module.activity.bo.*;
import cn.jualn.miniapp.module.activity.vo.*;
import cn.jualn.miniapp.module.activity.vo.admin.*;
import org.mapstruct.Mapper;
@Mapper(componentModel = "spring")
public interface ActivityRegistrationConverter {
    ActivityRegistrationVO toVO(ActivityRegistrationBO bo);
    AdminActivityRegistrationVO toAdminVO(ActivityRegistrationBO bo);
    ActivityFormVO toFormVO(ActivityFormBO bo);
    AdminActivityFormVO toAdminFormVO(ActivityFormBO bo);
}
