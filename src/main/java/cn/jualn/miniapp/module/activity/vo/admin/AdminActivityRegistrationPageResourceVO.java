package cn.jualn.miniapp.module.activity.vo.admin;

import java.util.List;

public record AdminActivityRegistrationPageResourceVO(
        List<AdminActivityRegistrationResourceVO> items,
        int page,
        int pageSize,
        long totalItems) {
}
