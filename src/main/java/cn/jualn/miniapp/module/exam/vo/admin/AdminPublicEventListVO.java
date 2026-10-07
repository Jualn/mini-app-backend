package cn.jualn.miniapp.module.exam.vo.admin;

import java.time.OffsetDateTime;

public record AdminPublicEventListVO(
        String publicEventId,
        String title,
        String publishStatus,
        String lifecycleStatus,
        OffsetDateTime updatedAt) {}
