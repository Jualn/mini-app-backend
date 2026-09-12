package cn.jualn.miniapp.module.activity.bo;

import com.fasterxml.jackson.databind.JsonNode;

public record ActivityFormBO(Long activityId, JsonNode formSchema, Integer registrationLimit,
        long submittedCount, boolean frozen, boolean enabled, String registrationStatus) {}
