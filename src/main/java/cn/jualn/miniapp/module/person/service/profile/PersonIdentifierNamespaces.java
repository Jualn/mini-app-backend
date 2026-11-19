package cn.jualn.miniapp.module.person.service.profile;

import cn.jualn.miniapp.module.person.bo.ManagedPersonBO.Namespace;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import static cn.jualn.miniapp.module.person.PersonProblems.invalid;
import static cn.jualn.miniapp.module.person.PersonProblems.unavailable;

/** Provider-owned technical namespace, never selected or narrowed by an organization. */
@Component
public class PersonIdentifierNamespaces {
    private final String namespace;
    private final String displayName;
    public PersonIdentifierNamespaces(@Value("${people-qualification.student-number-namespace:CAMPUS_STUDENT_NUMBER}") String namespace,
                                      @Value("${people-qualification.student-number-display-name:学号}") String displayName) {
        this.namespace = namespace; this.displayName = displayName;
    }
    public List<Namespace> items() {
        if (namespace == null || namespace.isBlank() || namespace.length() > 128
                || displayName == null || displayName.isBlank() || displayName.length() > 100) throw unavailable();
        return List.of(new Namespace("STUDENT_NUMBER", namespace, displayName));
    }
    void require(String type, String value) {
        if (items().stream().noneMatch(n -> n.identifierType().equals(type) && n.namespace().equals(value))) throw invalid("namespace");
    }
}
