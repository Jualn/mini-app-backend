package cn.jualn.miniapp.module.activity.bo;

import java.util.List;

public record ActivityRegistrationContractPageBO(
        List<ActivityRegistrationBO> items, int page, int pageSize, long totalItems) {
}
