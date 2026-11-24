package cn.jualn.miniapp.common.web;

/**
 * Minimal owner-provided name; absence and failed reads retain distinct states.
 */
public record NameReadBO(String id, String status, String name, AdminQuery.Problem problem) {
}
