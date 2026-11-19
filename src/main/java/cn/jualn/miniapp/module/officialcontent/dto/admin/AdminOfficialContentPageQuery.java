package cn.jualn.miniapp.module.officialcontent.dto.admin;
import lombok.Getter;
import lombok.Setter;
import cn.jualn.miniapp.module.officialcontent.dto.OfficialContentPageQuery;
import cn.jualn.miniapp.module.officialcontent.enums.OfficialContentStatus;
@Getter @Setter
public class AdminOfficialContentPageQuery extends OfficialContentPageQuery {
    private OfficialContentStatus publishStatus;
}
