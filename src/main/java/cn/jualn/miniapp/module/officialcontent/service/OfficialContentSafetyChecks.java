package cn.jualn.miniapp.module.officialcontent.service;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import java.util.List;

/** Checks immutable candidates outside the official content database transaction. */
public interface OfficialContentSafetyChecks {
    void text(long authenticatedAccountId, String content);
    void media(long authenticatedAccountId, long contentId, List<MediaAttachmentBO> attachments);
    static ContractProblemException unavailable() {
        return new ContractProblemException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "/problems/content-safety-check-unavailable", "Content safety checks are unavailable; no content was published");
    }
}
