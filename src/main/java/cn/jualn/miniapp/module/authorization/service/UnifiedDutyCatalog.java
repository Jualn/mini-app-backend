package cn.jualn.miniapp.module.authorization.service;

import cn.jualn.miniapp.module.authorization.model.RbacModels;
import cn.jualn.miniapp.module.authorization.support.RbacProblems;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.TreeSet;
import java.util.Comparator;

@Component
public class UnifiedDutyCatalog {
    public record Template(String dutyCode, String roleCode, String namespace, List<String> permissions,
                           Set<String> targetTypes) {
    }

    private final Map<String, Template> templates;
    private final RbacCatalog rbac;
    private final String digest;
    private static final Set<String> SCOPED = Set.of("PARTY_ORG_PERSONNEL_MANAGE", "PERSON_IDENTITY_MAINTAIN", "ORG_MEMBER_MAINTAIN", "POINTS_REVIEW", "POINTS_CORRECTION", "POINTS_PERSON_LEDGER", "POINTS_STATS_CURRENT", "POINTS_STATS_ACCEPTING", "LEARNING_MANAGE", "LEARNING_REVIEW", "ARTICLE_EDIT", "ARTICLE_PUBLISH", "PERSON_IDENTITY_READ", "ORG_MEMBER_READ", "PERSON_ORGANIZATION_READ");

    public boolean supported(String code) {
        return SCOPED.contains(code) || PlatformDutyPolicy.supports(code);
    }

    public String runtimeVersion(String rbacVersion) {
        return RbacCatalog.hash((rbacVersion + "|" + digest + "|owning-policies-r4-root-management-v1|" +
                templates().stream().filter(t -> supported(t.dutyCode())).map(Template::dutyCode).toList() + "|runtime-publication-v1").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public UnifiedDutyCatalog(RbacCatalog rbac) {
        this.rbac = rbac;
        var loaded = new LinkedHashMap<String, Template>();
        try (var in = new ClassPathResource("rbac/duty-templates-v1.json").getInputStream()) {
            var json = new ObjectMapper();
            byte[] bytes = in.readAllBytes();
            digest = RbacCatalog.hash(bytes);
            var root = json.readTree(bytes);
            for (var n : root.required("templates")) {
                List<String> permissions = json.readerForListOf(String.class).readValue(n.required("permissionCodes"));
                List<String> targets = json.readerForListOf(String.class).readValue(n.required("targetTypes"));
                var t = new Template(n.required("dutyCode").asText(), n.required("roleCode").asText(), n.required("namespace").asText(), permissions, Set.copyOf(targets));
                if ("ROOT".equals(t.roleCode()) || "AUTHORIZATION".equals(t.namespace())
                        || !new TreeSet<>(permissions).equals(new TreeSet<>(rbac.rolePermissions(t.roleCode(), t.namespace())))
                        || loaded.put(t.dutyCode(), t) != null)
                    throw new IllegalStateException("Invalid controlled duty mapping");
            }
            templates = Map.copyOf(loaded);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load controlled duty mappings", e);
        }
    }

    public List<Template> templates() {
        return templates.values().stream().sorted(Comparator.comparing(Template::dutyCode)).toList();
    }

    public Template template(String code) {
        var t = templates.get(code);
        if (t == null) throw RbacProblems.invalid("dutyCode");
        return t;
    }

    public boolean ready(String code, List<RbacModels.Definition> ps, List<RbacModels.Definition> rs) {
        var t = template(code);
        var pi = RbacAuthorizationReader.index(ps);
        var ri = RbacAuthorizationReader.index(rs);
        // Template registration is not Provider readiness. Add a duty only with its tested owning policy.
        if (!supported(code)) return false;
        // Canonical template readiness records registration evidence, not environment publication.
        return rbac.roleReady(t.roleCode(), ri, pi) && ri.get(t.roleCode()).isGrantable()
                && rbac.role(t.roleCode()).permissions().stream().allMatch(p -> pi.get(p).isGrantable());
    }
}
