package cn.jualn.miniapp.module.activity.service;

import cn.jualn.miniapp.module.activity.bo.RegistrationVersionBO;

import cn.jualn.miniapp.module.activity.bo.ActivityRegistrationPageBO;
import cn.jualn.miniapp.module.activity.bo.ActivityFormBO;
import cn.jualn.miniapp.module.activity.bo.ActivityRegistrationBO;
import cn.jualn.miniapp.module.activity.bo.ActivityRegistrationWriteBO;
import cn.jualn.miniapp.module.activity.bo.ActivityRegistrationContractPageBO;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collection;
import java.util.Map;

public interface ActivityRegistrationService {
    ActivityFormBO getForm(Long activityId);
    ActivityFormBO getAdminForm(Long activityId);
    ActivityRegistrationBO submit(Long activityId, JsonNode formData);
    ActivityRegistrationWriteBO submitContract(Long activityId, String formVersion, JsonNode answers, RegistrationVersionBO expectedVersion);
    ActivityRegistrationBO replaceMineContract(Long activityId, String formVersion, JsonNode answers, RegistrationVersionBO expectedVersion);
    ActivityRegistrationBO cancelMineContract(Long activityId, RegistrationVersionBO expectedVersion);
    ActivityRegistrationBO getMine(Long activityId);
    void cancelMine(Long activityId);
    ActivityRegistrationPageBO pageAdmin(Long activityId, Integer status, String cursor, int pageSize);
    ActivityRegistrationContractPageBO pageAdminContract(Long activityId, Integer status, int page, int pageSize);
    ActivityRegistrationBO getAdminRegistration(Long activityId, Long registrationId);
    void invalidate(Long activityId, Long registrationId, String reason);
    byte[] exportXlsx(Long activityId, Integer status);
    byte[] exportCsv(Long activityId, Integer status);
    Map<Long, Long> countSubmittedByActivityIds(Collection<Long> activityIds);
    /** Caller holds activity row lock; this service owns registration facts. */
    void validateFormChange(Long activityId, String oldSchema, String newSchema, Integer newLimit,
            Integer oldMode, Integer newMode, Integer participantMode);
}
