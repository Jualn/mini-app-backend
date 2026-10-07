package cn.jualn.miniapp.module.activity.dto.request;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
public record ActivityRegistrationRequest(@NotNull JsonNode formData) {}
