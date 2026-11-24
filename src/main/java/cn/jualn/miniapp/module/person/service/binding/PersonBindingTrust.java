package cn.jualn.miniapp.module.person.service.binding;

import cn.jualn.miniapp.module.person.entity.ManagedPersonRows;
import cn.jualn.miniapp.module.person.PersonProblems;
import org.springframework.http.HttpStatus;

/** Active binding provenance; business eligibility is evaluated by each domain. */
public final class PersonBindingTrust {
    private PersonBindingTrust() {}
    public static boolean trusted(ManagedPersonRows.Binding binding) {
        return binding != null && "ACTIVE".equals(binding.getStatus())
                && java.util.Set.of("ADMIN_MANUAL","ROSTER_EXACT_MATCH").contains(binding.getVerificationMethod()==null?"":binding.getVerificationMethod())
                && binding.getEndedAt()==null
                && binding.getSourceReference() != null && !binding.getSourceReference().isBlank();
    }
    public static void require(ManagedPersonRows.Binding binding) {
        if (!trusted(binding)) throw PersonProblems.problem(HttpStatus.FORBIDDEN, "person-binding-verification-required");
    }
}
