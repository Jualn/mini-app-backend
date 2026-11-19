package cn.jualn.miniapp.module.person.converter;

import cn.jualn.miniapp.module.person.bo.PersonBO;
import cn.jualn.miniapp.module.person.vo.PersonVO;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;

public final class PersonConverter {
    private PersonConverter() {}
    public static PersonVO.LocalMembership localMembership(PersonBO.LocalMembership b) {
        return new PersonVO.LocalMembership(b.personId(),b.organizationId(),b.verification(),b.presence(),b.membershipId(),time(b.validFrom()),b.version(),b.verifiedByUserId(),time(b.verifiedAt()),b.realName(),b.personStatus());
    }
    public static PersonVO.OrganizationMemberPage organizationMembers(PersonBO.OrganizationMemberPage b) {
        return new PersonVO.OrganizationMemberPage(b.organizationId(),b.coverage(),b.items().stream().map(PersonConverter::localMembership).toList(),b.page(),b.pageSize(),b.totalItems(),time(b.evaluatedAt()));
    }
    public static PersonVO.LocalHistory localHistory(PersonBO.LocalHistory b) {
        return new PersonVO.LocalHistory(b.changeId(),b.personId(),b.organizationId(),b.operation(),localMembership(b.before()),localMembership(b.after()),b.operatorUserId(),time(b.recordedAt()),b.reason(),b.verificationReference(),b.source());
    }
    public static String time(LocalDateTime value) { return value==null?null:DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value.atOffset(ZoneOffset.UTC)); }
    public static PersonVO.Identity identity(PersonBO.Identity b) {
        var r=b.currentRecord();var record=r==null?null:new PersonVO.IdentityRecord(r.recordId(),r.identityKind(),r.stageId(),r.status(),time(r.validFrom()),time(r.validUntil()));
        return new PersonVO.Identity(b.personId(),b.verification(),b.presence(),record,b.version(),b.lastChangeId(),b.verifiedByUserId(),time(b.verifiedAt()));
    }
    public static PersonVO.Memberships memberships(PersonBO.Memberships b) {
        return new PersonVO.Memberships(b.personId(),b.verification(),b.currentMemberships()==null?null:b.currentMemberships().stream().map(m->new PersonVO.Member(m.membershipId(),m.organizationId(),time(m.validFrom()),time(m.validUntil()))).toList(),b.version(),b.lastChangeId(),b.verifiedByUserId(),time(b.verifiedAt()),
                b.knownMemberships()==null?null:b.knownMemberships().stream().map(m->new PersonVO.Member(m.membershipId(),m.organizationId(),time(m.validFrom()),time(m.validUntil()))).toList(),b.knownMembershipsCoverage());
    }
    public static PersonVO.Organization organization(PersonBO.Organization b) { return new PersonVO.Organization(b.organizationId(),b.name(),b.description(),b.status(),b.version(),time(b.createdAt()),time(b.updatedAt()),b.type()); }
    public static PersonVO.Scope scope(PersonBO.Scope b) { return new PersonVO.Scope(b.scopeGrantId(),b.granteeUserId(),b.mode(),b.target(),b.actionSet(),b.status(),time(b.validFrom()),time(b.validUntil()),b.grantedByUserId(),b.reason(),b.verificationReference(),b.source(),b.authorityGrantId(),b.version(),time(b.revokedAt()),b.revokedByUserId(),b.revokeReason()); }
    public static PersonVO.Summary summary(PersonBO.Summary b) { return new PersonVO.Summary(b.personId(),b.realName(),b.status(),b.identityVerification(),b.membershipVerification(),b.disambiguation()); }
    public static <T,R> PersonVO.History<R> history(PersonBO.History<T> b,Function<T,R> convert) { return new PersonVO.History<>(b.changeId(),b.personId(),b.operation(),convert.apply(b.before()),convert.apply(b.after()),b.operatorUserId(),time(b.recordedAt()),b.reason(),b.verificationReference(),b.source()); }
    public static <T,R> PersonVO.Page<R> page(PersonBO.Page<T> b,Function<T,R> convert) { return new PersonVO.Page<>(b.items().stream().map(convert).toList(),b.page(),b.pageSize(),b.totalItems()); }
}
