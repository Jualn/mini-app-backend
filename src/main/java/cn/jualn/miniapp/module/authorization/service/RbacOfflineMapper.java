package cn.jualn.miniapp.module.authorization.service;

import cn.jualn.miniapp.module.authorization.service.RbacOfflineTool;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Deliberately no @Mapper: only the explicit offline context registers this writer.
 */
interface RbacOfflineMapper {
    String serverVersion();

    RbacOfflineTool.Environment environment();

    RbacOfflineTool.StoredOperation operation(String id);

    List<RbacOfflineTool.StoredEffect> effects(String id);

    long rootHistory();

    int switchSource(@Param("user") long user, @Param("source") String source,
                     @Param("version") String version, @Param("next") String next);

    int insertOperation(RbacOfflineTool.StoredOperation operation);

    int insertEffect(@Param("operation") String operation, @Param("effect") RbacOfflineTool.Effect effect,
                     @Param("before") String before, @Param("after") String after);
}
