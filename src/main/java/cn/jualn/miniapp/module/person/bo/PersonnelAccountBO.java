package cn.jualn.miniapp.module.person.bo;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/** The same Person/account relation, authorized by each owning business boundary. */
public final class PersonnelAccountBO {
    private PersonnelAccountBO() {}
    public record AccountOption(String userId, String nickname, boolean available) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BindingView(String personId, String userId, String nickname, String bindingId, String version, Instant evaluatedAt) {}
    public record BindingChange(String action, String userId, String reason, String verificationReference) {}
}
