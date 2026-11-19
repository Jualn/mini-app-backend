package cn.jualn.miniapp.module.authorization.service;

import cn.jualn.miniapp.module.authorization.support.RbacProblems;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import cn.jualn.miniapp.module.authorization.model.RbacModels.*;

/**
 * Frozen canonical artifact plus an explicit, release-owned support list. No runtime enable API.
 */
@Component
public class RbacCatalog {
    public record Entry(String code, Set<String> namespaces, Set<String> permissions, String description, String name) {
        public Entry {
            namespaces = Set.copyOf(namespaces);
            permissions = Set.copyOf(permissions);
        }
    }

    private final Map<String, Entry> permissions;
    private final Map<String, Entry> roles;
    private final Set<String> supported;
    private final Set<String> namespaces;
    private final String digest;
    private final ObjectMapper json = new ObjectMapper();

    static final Set<String> LEARNING_TASK_INTEGRATION_SUPPORT = Set.of(
            "audit-log:read", "authorization:read", "authorization:assignment-manage", "authorization:root-manage",
            "learning-task:read", "learning-task:edit", "learning-task:assign", "learning-task:publish",
            "learning-task:lifecycle", "learning-task:revoke");

    private static Set<String> implementedSupport() {
        try (var input = new ClassPathResource("rbac/local-admin-test.json").getInputStream()) {
            var nodes = new ObjectMapper().readTree(input).get("permissionCodes");
            Set<String> codes = new HashSet<>();
            for (var node : nodes)
                if (!node.isTextual() || !codes.add(node.asText()))
                    throw new IllegalStateException("Invalid implemented management permission list");
            codes.addAll(Set.of("person-scope:read", "person-scope:manage",
                    "mentor:qualification-manage", "points:acceptance-manage",
                    "points:read", "points:review", "points:adjust", "points:adjust-approve", "activity:registration:dispose", "home-banner:manage", "party-personnel:manage"));
            return Set.copyOf(codes);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load implemented management support", e);
        }
    }

    public RbacCatalog() {
        this(implementedSupport());
    }

    RbacCatalog(Set<String> supported) {
        try (var stream = new ClassPathResource("rbac/rbac-v1.json").getInputStream()) {
            byte[] bytes = stream.readAllBytes();
            digest = hash(bytes);
            JsonNode root = json.readTree(bytes);
            namespaces = strings(root.get("namespaces"));
            permissions = load(root.get("permissions"), false);
            roles = load(root.get("roles"), true);
            if (!permissions.keySet().containsAll(supported))
                throw new IllegalArgumentException("Unknown supported permission");
            this.supported = Set.copyOf(supported);
            for (Entry r : roles.values()) {
                if (!permissions.keySet().containsAll(r.permissions()))
                    throw new IllegalStateException("Unknown role member");
                for (String space : r.namespaces())
                    if (r.permissions().stream().noneMatch(p -> permissions.get(p).namespaces().contains(space)))
                        throw new IllegalStateException("Empty role namespace");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read controlled RBAC artifact", e);
        }
    }

    private Map<String, Entry> load(JsonNode nodes, boolean role) {
        Map<String, Entry> result = new HashMap<>();
        for (JsonNode node : nodes) {
            String code = node.get("code").asText();
            if (result.put(code, new Entry(code, strings(node.get("namespaces")), role ? strings(node.get("permissionCodes")) : Set.of(),
                    role ? null : node.get("description").asText(), role ? node.get("name").asText() : null)) != null)
                throw new IllegalStateException("Duplicate controlled code");
        }
        return Map.copyOf(result);
    }

    private Set<String> strings(JsonNode array) {
        Set<String> values = new HashSet<>();
        array.forEach(n -> values.add(n.asText()));
        return Set.copyOf(values);
    }

    public String digest() {
        return digest;
    }

    /**
     * Release-owned support only. This does not enable definitions or make a role grantable.
     */
    public boolean supports(String namespace, Set<String> codes) {
        return !codes.isEmpty() && codes.stream().allMatch(code -> !excluded(code) && supported.contains(code)
                && permissions.containsKey(code) && permissions.get(code).namespaces().contains(namespace));
    }

    static final Set<String> PERSON_OWNER_PERMISSIONS = Set.of("identity:read", "identity:map-manage",
            "organization:read", "organization:map-manage", "person-scope:read", "person-scope:manage");

    List<OwnerCapability> ownerCapabilities(List<Grant> grants, Map<String, Definition> definitions) {
        var root = grants.stream().filter(g -> "ROOT".equals(g.roleCode()) && g.expiresAt() == null
                && g.allows("authorization:root-manage", "AUTHORIZATION")).findFirst();
        if (root.isEmpty()) return List.of();
        Map<String, List<String>> byNamespace = new TreeMap<>();
        permissions.values().stream().sorted(Comparator.comparing(Entry::code))
                .filter(p -> !excluded(p.code()) && permissionReady(p.code(), definitions)).forEach(p -> p.namespaces().stream()
                        .filter(n -> !"AUTHORIZATION".equals(n)).sorted().forEach(n ->
                                byNamespace.computeIfAbsent(n, ignored -> new ArrayList<>()).add(p.code())));
        return byNamespace.entrySet().stream().map(e -> new OwnerCapability(root.get().assignmentId(), e.getKey(), "GLOBAL", e.getValue())).toList();
    }

    boolean namespaceKnown(String namespace) {
        return namespaces.contains(namespace);
    }

    String runtimeVersion(List<Definition> permissionRows, List<Definition> roleRows) {
        StringBuilder state = new StringBuilder(digest);
        supported.stream().sorted().forEach(p -> state.append('|').append(p));
        for (List<Definition> rows : List.of(permissionRows, roleRows)) {
            state.append('|');
            rows.stream().sorted(Comparator.comparing(Definition::getCode)).forEach(r -> state.append(r.getCode()).append(':')
                    .append(r.getLifecycle()).append(':').append(r.getReadiness()).append(':').append(r.isGrantable()).append(':')
                    .append(r.getUnavailableReason()).append(';'));
        }
        return hash(state.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    Entry role(String code) {
        return roles.get(code);
    }

    Entry permission(String code) {
        return permissions.get(code);
    }

    /**
     * Controlled role membership in exactly one namespace, independent of runtime readiness.
     * Shared by authorization and safe explanations; a role name never supplies extra actions.
     */
    public List<String> rolePermissions(String roleCode, String namespace) {
        Entry entry = roles.get(roleCode);
        if (entry == null || !entry.namespaces().contains(namespace)) return List.of();
        return entry.permissions().stream()
                .filter(code -> permissions.get(code).namespaces().contains(namespace)).sorted().toList();
    }

    void verify(List<Definition> permissionRows, List<Definition> roleRows, List<Member> memberRows) {
        verifyRows(permissionRows, permissions);
        verifyRows(roleRows, roles);
        Set<Member> expected = new HashSet<>();
        roles.values().forEach(r -> r.permissions().forEach(p -> expected.add(new Member(r.code(), p))));
        if (memberRows.size() != expected.size() || !expected.equals(new HashSet<>(memberRows)))
            throw RbacProblems.unavailable();
    }

    private void verifyRows(List<Definition> rows, Map<String, Entry> entries) {
        if (rows.size() != entries.size()) throw RbacProblems.unavailable();
        Set<String> seen = new HashSet<>();
        for (Definition row : rows) {
            Entry entry = entries.get(row.getCode());
            try {
                if (entry == null || !seen.add(row.getCode()) || !digest.equals(row.getCatalogDigest())
                        || !entry.namespaces().equals(strings(json.readTree(row.getNamespaces())))
                        || !Set.of("REGISTERED", "ENABLED", "DISABLED").contains(row.getLifecycle())
                        || !Set.of("NOT_READY", "READY").contains(row.getReadiness())
                        || row.isGrantable() && (!enabled(row) || row.getUnavailableReason() != null))
                    throw RbacProblems.unavailable();
            } catch (IOException | IllegalArgumentException e) {
                throw RbacProblems.unavailable();
            }
        }
    }

    boolean enabled(Definition row) {
        return row != null && "ENABLED".equals(row.getLifecycle()) && "READY".equals(row.getReadiness());
    }

    public static boolean excluded(String code) {
        return "learning-task:revoke-participated".equals(code);
    }

    boolean permissionReady(String code, Map<String, Definition> definitions) {
        return !excluded(code) && supported.contains(code) && enabled(definitions.get(code));
    }

    boolean roleReady(String code, Map<String, Definition> roleDefinitions, Map<String, Definition> permissionDefinitions) {
        Entry entry = roles.get(code);
        return entry != null && enabled(roleDefinitions.get(code)) && entry.permissions().stream().allMatch(p -> permissionReady(p, permissionDefinitions));
    }

    static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
