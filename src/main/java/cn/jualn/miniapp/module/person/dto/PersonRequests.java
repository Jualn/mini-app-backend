package cn.jualn.miniapp.module.person.dto;

import cn.jualn.miniapp.module.person.bo.PersonBO.Action;
import cn.jualn.miniapp.module.person.bo.PersonBO.ActionSet;
import cn.jualn.miniapp.module.person.bo.PersonBO.ChangeKind;
import cn.jualn.miniapp.module.person.bo.PersonBO.Domain;
import cn.jualn.miniapp.module.person.bo.PersonBO.IdentityChange;
import cn.jualn.miniapp.module.person.bo.PersonBO.LocalMemberChange;
import cn.jualn.miniapp.module.person.bo.PersonBO.LocalMemberKind;
import cn.jualn.miniapp.module.person.bo.PersonBO.MemberInput;
import cn.jualn.miniapp.module.person.bo.PersonBO.MembershipChange;
import cn.jualn.miniapp.module.person.bo.PersonBO.OrganizationChange;
import cn.jualn.miniapp.module.person.bo.PersonBO.Reason;
import cn.jualn.miniapp.module.person.bo.PersonBO.ScopeChange;
import cn.jualn.miniapp.module.person.bo.PersonBO.Target;
import cn.jualn.miniapp.module.person.bo.PersonBO.Verification;

import cn.jualn.miniapp.module.person.service.facts.PersonFoundationService;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static cn.jualn.miniapp.module.person.PersonProblems.invalid;

/** Closed local parsing; no global coercion or actor/role/organization injection. */
public final class PersonRequests {
    private PersonRequests() {}
    public record Roster(cn.jualn.miniapp.module.person.bo.PersonBO.RosterChange command) {
        @JsonCreator(mode=JsonCreator.Mode.DELEGATING) public static Roster parse(JsonNode n) {
            closed(n,"action","membershipVersion","reason","verificationReference");
            return new Roster(new cn.jualn.miniapp.module.person.bo.PersonBO.RosterChange(string(n,"action",16),string(n,"membershipVersion",128),string(n,"reason",2000),manualReference(n)));
        }
    }
    public record LocalMember(LocalMemberChange command) {
        @JsonCreator(mode=JsonCreator.Mode.DELEGATING) public static LocalMember parse(JsonNode n) {
            closed(n,"kind","reason","verificationReference");
            return new LocalMember(new LocalMemberChange(enumeration(n,"kind",LocalMemberKind.class),string(n,"reason",2000),manualReference(n)));
        }
    }
    public record Identity(IdentityChange command) {
        @JsonCreator(mode=JsonCreator.Mode.DELEGATING) public static Identity parse(JsonNode n) {
            closed(n,"kind","stageId","validUntil","correctsChangeId","reason","verificationReference");
            var kind=enumeration(n,"kind",ChangeKind.class);var stage=optional(n,"stageId",64);
            if(stage!=null && !Set.of("PARTY_ACTIVIST","PARTY_DEVELOPMENT_CANDIDATE","PROBATIONARY_PARTY_MEMBER","FULL_PARTY_MEMBER").contains(stage)) throw invalid("stageId");
            var command=new IdentityChange(kind,stage,time(n,"validUntil"),optional(n,"correctsChangeId",128),string(n,"reason",2000),manualReference(n));
            PersonFoundationService.validate(command);return new Identity(command);
        }
    }
    public record Memberships(MembershipChange command) {
        @JsonCreator(mode=JsonCreator.Mode.DELEGATING) public static Memberships parse(JsonNode n) {
            closed(n,"verification","memberships","reason","verificationReference");List<MemberInput> members=null;
            if(n.has("memberships")) {
                var items=n.get("memberships");if(!items.isArray() || items.size()>100) throw invalid("memberships");members=new ArrayList<>();
                for(var item:items) { closed(item,"organizationId","validUntil");members.add(new MemberInput(string(item,"organizationId",128),time(item,"validUntil"))); }
            }
            var command=new MembershipChange(enumeration(n,"verification",Verification.class),members,string(n,"reason",2000),manualReference(n));PersonFoundationService.validate(command);return new Memberships(command);
        }
    }
    public record CreateOrganization(OrganizationChange command) {
        @JsonCreator(mode=JsonCreator.Mode.DELEGATING) public static CreateOrganization parse(JsonNode n) {
            closed(n,"name","description","reason","verificationReference","type");var c=new OrganizationChange(string(n,"name",120),description(n),null,string(n,"reason",2000),string(n,"verificationReference",256),string(n,"type",32));PersonFoundationService.validate(c,true);return new CreateOrganization(c);
        }
    }
    public record EditOrganization(OrganizationChange command) {
        @JsonCreator(mode=JsonCreator.Mode.DELEGATING) public static EditOrganization parse(JsonNode n) {
            closed(n,"name","description","status","reason","verificationReference","type");var c=new OrganizationChange(optional(n,"name",120),n.has("description")?description(n):null,optional(n,"status",16),string(n,"reason",2000),string(n,"verificationReference",256),optional(n,"type",32));PersonFoundationService.validate(c,false);return new EditOrganization(c);
        }
    }
    public record Grant(ScopeChange command) {
        @JsonCreator(mode=JsonCreator.Mode.DELEGATING) public static Grant parse(JsonNode n) {
            closed(n,"granteeUserId","target","actionSet","validUntil","reason","verificationReference");var target=n.get("target");
            String type=string(target,"type",16);Target t;
            if("PERSON".equals(type)) { closed(target,"type","personId");t=new Target(type,string(target,"personId",128),null); }
            else if("ORGANIZATION".equals(type)) { closed(target,"type","organizationId");t=new Target(type,null,string(target,"organizationId",128)); }
            else throw invalid("target");
            var set=n.get("actionSet");closed(set,"domain","actions");var array=set.get("actions");if(array==null || !array.isArray()) throw invalid("actions");
            List<Action> actions=new ArrayList<>();for(var a:array) { if(!a.isTextual()) throw invalid("actions");try { actions.add(Action.valueOf(a.textValue())); } catch(IllegalArgumentException e) { throw invalid("actions"); } }
            var c=new ScopeChange(string(n,"granteeUserId",128),t,new ActionSet(enumeration(set,"domain",Domain.class),actions),time(n,"validUntil"),string(n,"reason",2000),string(n,"verificationReference",256));PersonFoundationService.validate(c);return new Grant(c);
        }
    }
    public record Revoke(Reason command) {
        @JsonCreator(mode=JsonCreator.Mode.DELEGATING) public static Revoke parse(JsonNode n) {
            closed(n,"reason","verificationReference");return new Revoke(new Reason(string(n,"reason",2000),string(n,"verificationReference",256)));
        }
    }
    private static void closed(JsonNode n,String... fields) {
        if(n==null || !n.isObject()) throw invalid("");Set<String> names=Set.of(fields);n.fieldNames().forEachRemaining(name->{if(!names.contains(name)) throw invalid(name);});
    }
    private static String string(JsonNode n,String name,int max) {
        if(n==null || !n.isObject() || !n.has(name) || !n.get(name).isTextual()) throw invalid(name);
        var value=n.get(name).textValue();PersonFoundationService.text(value,max,name,false);return value;
    }
    private static String optional(JsonNode n,String name,int max) { return n.has(name)?string(n,name,max):null; }
    // This records absence of material honestly; it is not an evidence identifier.
    private static String manualReference(JsonNode n) { return n.has("verificationReference")?string(n,"verificationReference",256):"MANUAL_CONFIRMATION_NO_MATERIAL"; }
    private static String description(JsonNode n) { if(!n.has("description") || !n.get("description").isTextual()) throw invalid("description");String s=n.get("description").textValue();PersonFoundationService.text(s,2000,"description",true);return s; }
    private static <T extends Enum<T>> T enumeration(JsonNode n,String name,Class<T> type) { try { return Enum.valueOf(type,string(n,name,64)); } catch(IllegalArgumentException e) { throw invalid(name); } }
    private static LocalDateTime time(JsonNode n,String name) {
        if(!n.has(name)) return null;String value=string(n,name,40);
        if(!value.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,6})?(?:Z|[+-]\\d{2}:\\d{2})")) throw invalid(name);
        try { return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(); } catch(java.time.DateTimeException failure) { throw invalid(name); }
    }
}
