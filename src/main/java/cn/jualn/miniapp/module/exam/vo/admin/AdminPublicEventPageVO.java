package cn.jualn.miniapp.module.exam.vo.admin;

import java.util.List;

public record AdminPublicEventPageVO(
        List<AdminPublicEventListVO> items,
        int page,
        int pageSize,
        long totalItems) {}
