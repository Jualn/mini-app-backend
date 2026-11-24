package cn.jualn.miniapp.module.officialcontent.service;
import cn.jualn.miniapp.module.officialcontent.bo.*;
import cn.jualn.miniapp.module.officialcontent.enums.*;
public interface OfficialContentService {
    OfficialContentPageBO list(OfficialContentCategory category, OfficialContentStatus status, String cursor, int size, boolean admin);
    OfficialContentBO get(long id, boolean admin);
    OfficialContentBO create(OfficialContentSaveBO command);
    OfficialContentBO replace(long id, OfficialContentSaveBO command, String ifMatch);
    OfficialContentBO publication(long id,boolean publish,String ifMatch);
    boolean isPubliclyVisible(long id);
}
