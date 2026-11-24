package cn.jualn.miniapp.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

public final class AdminNamesVO {
    private AdminNamesVO() {
    }

    public sealed interface UserItem permits UserReady, UserUnavailable, UserError {
    }

    public sealed interface OrganizationItem permits OrganizationReady, OrganizationUnavailable, OrganizationError {
    }

    public record Users(Instant evaluatedAt, List<UserItem> items) {
    }

    public record Organizations(Instant evaluatedAt, List<OrganizationItem> items) {
    }

    public record UserReady(String userId, String status,
                            @JsonInclude(JsonInclude.Include.ALWAYS) String name) implements UserItem {
    }

    public record UserUnavailable(String userId, String status) implements UserItem {
    }

    public record UserError(String userId, String status, AdminQuery.Problem problem) implements UserItem {
    }

    public record OrganizationReady(String organizationId, String status, String name) implements OrganizationItem {
    }

    public record OrganizationUnavailable(String organizationId, String status) implements OrganizationItem {
    }

    public record OrganizationError(String organizationId, String status,
                                    AdminQuery.Problem problem) implements OrganizationItem {
    }

    public static UserItem user(NameReadBO value) {
        return switch (value.status()) {
            case "READY" -> new UserReady(value.id(), value.status(), value.name());
            case "UNAVAILABLE" -> new UserUnavailable(value.id(), value.status());
            case "ERROR" -> new UserError(value.id(), value.status(), value.problem());
            default -> throw new IllegalArgumentException("Unknown name read state");
        };
    }

    public static OrganizationItem organization(NameReadBO value) {
        return switch (value.status()) {
            case "READY" -> new OrganizationReady(value.id(), value.status(), value.name());
            case "UNAVAILABLE" -> new OrganizationUnavailable(value.id(), value.status());
            case "ERROR" -> new OrganizationError(value.id(), value.status(), value.problem());
            default -> throw new IllegalArgumentException("Unknown name read state");
        };
    }
}
