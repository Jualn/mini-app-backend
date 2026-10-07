package cn.jualn.miniapp.module.activity.vo.admin;
import com.fasterxml.jackson.databind.JsonNode;
public record AdminActivityFormVO(Long activityId, JsonNode formSchema, Integer registrationLimit,
        long submittedCount, boolean frozen, boolean enabled, String registrationStatus) {}
