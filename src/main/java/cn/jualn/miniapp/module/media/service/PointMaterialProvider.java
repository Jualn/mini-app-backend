package cn.jualn.miniapp.module.media.service;

import cn.jualn.miniapp.module.media.bo.PointMaterialBO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Private retention adapter: validates actual bytes and applies the current kind-specific safety policy.
 */
public interface PointMaterialProvider {
    PointMaterialBO.Credentials credentials(String uploadId, List<String> keys, LocalDateTime expiresAt);

    void retainAndInspect(String source, String retained, long owner, PointMaterialBO.Declaration declaration);

    PointMaterialBO.Access access(String retained, LocalDateTime expiresAt);

    void delete(String key);
}
