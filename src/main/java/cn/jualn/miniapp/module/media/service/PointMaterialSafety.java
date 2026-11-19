package cn.jualn.miniapp.module.media.service;

import cn.jualn.miniapp.module.media.bo.PointMaterialBO.Declaration;

import java.nio.file.Path;

/**
 * Applies the declared kind-specific policy to an immutable private snapshot; missing image verdict fails closed.
 */
public interface PointMaterialSafety {
    void requireSafe(long owner, String retained, Path snapshot, Declaration declaration);
}
