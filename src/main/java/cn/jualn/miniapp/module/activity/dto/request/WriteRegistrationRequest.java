package cn.jualn.miniapp.module.activity.dto.request;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Complete answers for a published form; answer values are deliberately dynamic. */
public record WriteRegistrationRequest(
        @NotBlank @Size(max = 128) String formVersion,
        @NotNull JsonNode answers) {
}
