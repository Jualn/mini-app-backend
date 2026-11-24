package cn.jualn.miniapp.module.media.service;

import cn.jualn.miniapp.common.enums.TargetType;

/** Bounded evidence reading supplied by the business owner; never authorizes ordinary-session media. */
public interface MediaEvidencePolicy {
    boolean mayReadEvidence(TargetType type, long targetId, long actor);
}
