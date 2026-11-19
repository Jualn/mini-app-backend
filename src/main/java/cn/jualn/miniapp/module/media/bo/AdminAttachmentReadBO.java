package cn.jualn.miniapp.module.media.bo;

import cn.jualn.miniapp.common.web.AdminQuery;

public record AdminAttachmentReadBO(Long attachmentId, String status, MediaAttachmentBO attachment,
                                    AdminQuery.Problem problem) {}
