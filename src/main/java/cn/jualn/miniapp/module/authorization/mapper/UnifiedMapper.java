package cn.jualn.miniapp.module.authorization.mapper;

import cn.jualn.miniapp.module.authorization.model.RbacModels;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

import static cn.jualn.miniapp.module.authorization.model.UnifiedModels.AuthorityRow;
import static cn.jualn.miniapp.module.authorization.model.UnifiedModels.PreviewRow;
import static cn.jualn.miniapp.module.authorization.model.UnifiedModels.CommandRow;
import static cn.jualn.miniapp.module.authorization.model.UnifiedModels.CursorRow;

@Mapper
public interface UnifiedMapper {
    AuthorityRow authority(String id);

    List<AuthorityRow> authorities();

    int insertAuthority(AuthorityRow row);

    int revokeAuthority(@Param("id") String id, @Param("actor") long actor, @Param("reason") String reason, @Param("now") LocalDateTime now);

    PreviewRow preview(String id);

    int insertPreview(PreviewRow row);

    CommandRow command(@Param("actor") long actor, @Param("operation") String operation, @Param("target") String target, @Param("key") String key);

    int insertCommand(CommandRow row);

    CommandRow commandById(String id);

    int insertAssignment(RbacModels.Assignment assignment);

    CursorRow cursor(String id);

    int insertCursor(CursorRow row);
}
