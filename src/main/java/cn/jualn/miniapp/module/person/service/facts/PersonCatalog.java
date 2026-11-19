package cn.jualn.miniapp.module.person.service.facts;

import java.io.IOException;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Controlled release artifact only. Never infers transition or Points policy from stage order. */
@Component
public class PersonCatalog {
    public record Stage(String stageId,String displayName,String confirmationStatus) {}
    public record Catalog(String catalogVersion,String policyConfirmationStatus,List<Stage> stages) {
        public Catalog { stages=List.copyOf(stages); }
    }
    private final Catalog value;
    private final String approvalEvidence;
    private final String pointsStatus;
    private final String pointsApprovalEvidence;
    private final List<String> eligibleStages;
    public PersonCatalog() {
        try(var stream=new ClassPathResource("person/person-foundation-v1.json").getInputStream()) {
            var json=new ObjectMapper();var root=json.readTree(stream);
            value=new Catalog(root.required("catalogVersion").asText(),root.required("policyConfirmationStatus").asText(),
                    json.readerForListOf(Stage.class).readValue(root.required("stages")));
            approvalEvidence=root.path("approvalEvidence").asText("");
            var points=root.required("pointsEligibilityPolicy");
            pointsStatus=points.required("confirmationStatus").asText();
            pointsApprovalEvidence=points.path("approvalEvidence").asText("");
            eligibleStages=List.copyOf(json.readerForListOf(String.class).readValue(points.required("eligibleStageIds")));
            java.util.List<String> requirements=json.readerForListOf(String.class).readValue(points.required("requirements"));
            if(!java.util.Set.copyOf(requirements).equals(java.util.Set.of("VERIFIED","ACTIVE","WITHIN_VALIDITY","ACCOUNT_AVAILABLE","VERIFIED_ACTIVE_MEMBERSHIP","ACTIVE_ACCEPTING_ORGANIZATION")))
                throw new IllegalStateException("Unsupported controlled Points eligibility requirements");
            if(value.stages().size()!=4 || value.stages().stream().map(Stage::stageId).distinct().count()!=4
                    || !List.of("PROPOSED","CONFIRMED").contains(value.policyConfirmationStatus())
                    || "CONFIRMED".equals(value.policyConfirmationStatus()) && (approvalEvidence.isBlank()
                    || value.stages().stream().anyMatch(s->!"CONFIRMED".equals(s.confirmationStatus())))
                    || !List.of("PROPOSED","CONFIRMED").contains(pointsStatus)
                    || "CONFIRMED".equals(pointsStatus) && (pointsApprovalEvidence.isBlank() || eligibleStages.isEmpty()
                    || eligibleStages.stream().anyMatch(id->value.stages().stream().noneMatch(s->s.stageId().equals(id)))))
                throw new IllegalStateException("Controlled policy approval or stage set is invalid");
        } catch(IOException failure) { throw new IllegalStateException("Controlled person catalog unavailable",failure); }
    }
    public Catalog value() { return value; }
    public boolean confirmed(String stage) {
        return value.stages().stream().anyMatch(s->s.stageId().equals(stage) && ("CONFIRMED".equals(value.policyConfirmationStatus()) && !approvalEvidence.isBlank() && "CONFIRMED".equals(s.confirmationStatus())));
    }
    public boolean pointsPolicyConfirmed() {
        return "CONFIRMED".equals(pointsStatus) && !pointsApprovalEvidence.isBlank()
                && "CONFIRMED".equals(value.policyConfirmationStatus()) && !approvalEvidence.isBlank();
    }
    public boolean pointsPolicyReady() { return pointsPolicyConfirmed(); }
    public boolean pointsEligibleStage(String stage) { return pointsPolicyReady() && eligibleStages.contains(stage) && confirmed(stage); }
    public String pointsPolicySource() { return pointsPolicyConfirmed()?"CONFIRMED_LOCAL_PARTY_V1":"PROPOSED_POLICY_CLOSED"; }
}
