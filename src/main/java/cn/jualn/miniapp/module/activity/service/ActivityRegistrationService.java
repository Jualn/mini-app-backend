package cn.jualn.miniapp.module.activity.service;

import cn.jualn.miniapp.module.activity.bo.ActivityRegistrationPageBO;
import cn.jualn.miniapp.module.activity.bo.ActivityFormBO;
import cn.jualn.miniapp.module.activity.bo.ActivityRegistrationBO;
import com.fasterxml.jackson.databind.JsonNode;

public interface ActivityRegistrationService {
    ActivityFormBO getForm(Long activityId);
    ActivityFormBO getAdminForm(Long activityId);
    ActivityRegistrationBO submit(Long activityId, JsonNode formData);
    ActivityRegistrationBO getMine(Long activityId);
    void cancelMine(Long activityId);
    ActivityRegistrationPageBO pageAdmin(Long activityId, Integer status, String cursor, int pageSize);
    ActivityRegistrationBO getAdminRegistration(Long activityId, Long registrationId);
    void invalidate(Long activityId, Long registrationId, String reason);
    byte[] export(Long activityId, Integer status);
    /** Caller holds activity row lock; this service owns registration facts. */
    void validateFormChange(Long activityId, String oldSchema, String newSchema, Integer newLimit,
            Integer oldMode, Integer newMode, Integer participantMode);
}
