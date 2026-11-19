package cn.jualn.miniapp.module.media.service;

import cn.jualn.miniapp.module.media.bo.GrowthMediaBO.Scope;

/** Owner callback for fresh candidate reads; Media never decides Person or organization eligibility. */
public interface GrowthMediaAuthorization {
    boolean candidate(Scope scope);
}
