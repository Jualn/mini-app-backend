package cn.jualn.miniapp.module.person.service.access;

import cn.jualn.miniapp.module.person.entity.PersonRows;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PersonGrowthQualificationTest {
    PersonRows.Head head(String stage){var h=new PersonRows.Head();h.setIdentityVerification("VERIFIED");h.setIdentityPresence("RECORDED");h.setIdentityStatus("ACTIVE");h.setIdentityVerifiedAt(LocalDateTime.of(2026,1,1,0,0));h.setIdentityVerifiedBy(1L);h.setIdentityValidFrom(h.getIdentityVerifiedAt());h.setStageId(stage);return h;}
    @Test void onlyFourFormalStagesPublishAndBirthdayRequiresMembershipStage(){
        var now=LocalDateTime.of(2026,11,1,0,0);
        for(String stage:List.of("PARTY_ACTIVIST","PARTY_DEVELOPMENT_CANDIDATE"))assertEquals(List.of("GROWTH"),PersonGrowthQualification.types(head(stage),now));
        for(String stage:List.of("PROBATIONARY_PARTY_MEMBER","FULL_PARTY_MEMBER"))assertEquals(List.of("GROWTH","POLITICAL_BIRTHDAY"),PersonGrowthQualification.types(head(stage),now));
        assertTrue(PersonGrowthQualification.types(head("EXPECTED_PARTY_MEMBER"),now).isEmpty());
    }
    @Test void validityIsHalfOpenAndHistoricalVerificationCannotReplaceCurrentIdentity(){
        var now=LocalDateTime.of(2026,11,1,0,0);var h=head("FULL_PARTY_MEMBER");h.setIdentityValidUntil(now);assertTrue(PersonGrowthQualification.types(h,now).isEmpty());
        h.setIdentityValidUntil(null);h.setIdentityValidFrom(now.plusSeconds(1));assertTrue(PersonGrowthQualification.types(h,now).isEmpty());
        h.setIdentityValidFrom(now);h.setIdentityVerification("UNKNOWN");assertTrue(PersonGrowthQualification.types(h,now).isEmpty());
        h.setIdentityVerification("VERIFIED");h.setIdentityStatus("INACTIVE");assertTrue(PersonGrowthQualification.types(h,now).isEmpty());
    }
    @Test void trustedRosterBindingAndLocalPositiveMembershipDoNotRequireGlobalRosterCompleteness(){
        var facts=mock(cn.jualn.miniapp.module.person.mapper.PersonMapper.class);var profiles=mock(cn.jualn.miniapp.module.person.mapper.ManagedPersonMapper.class);var service=new PersonGrowthQualification(facts,profiles,mock(cn.jualn.miniapp.module.authorization.service.TransactionalAuthorization.class));
        var at=LocalDateTime.of(2026,11,1,0,0);when(facts.now()).thenReturn(at);
        var binding=new cn.jualn.miniapp.module.person.entity.ManagedPersonRows.Binding();binding.setId("binding");binding.setPersonId("person");binding.setStatus("ACTIVE");binding.setVerificationMethod("ROSTER_EXACT_MATCH");binding.setSourceReference("confirmed-roster");when(profiles.accountBinding(1)).thenReturn(binding);
        var profile=new cn.jualn.miniapp.module.person.entity.ManagedPersonRows.Profile();profile.setStatus("ACTIVE");when(profiles.profile("person")).thenReturn(profile);var h=head("FULL_PARTY_MEMBER");h.setMembershipVerification("UNKNOWN");when(facts.head("person")).thenReturn(h);
        var org=new PersonRows.Organization();org.setId("branch");org.setName("branch");org.setType("PARTY_BRANCH");org.setStatus("ACTIVE");when(facts.organization("branch")).thenReturn(org);
        var member=new PersonRows.Membership();member.setId("cycle");member.setOrganizationId("branch");member.setValidFrom(at.minusDays(1));when(facts.memberships("person")).thenReturn(List.of(member));
        var local=new PersonRows.LocalMembership();local.setPresence("PRESENT");local.setMembershipId("cycle");local.setVerifiedAt(at.minusDays(1));local.setVerifiedBy(9);when(facts.localMembership("person","branch")).thenReturn(local);
        assertEquals("person",service.requirePublisher(1,"branch","POLITICAL_BIRTHDAY"));assertTrue(service.member(1,"branch"));
        member.setValidUntil(at);assertFalse(service.member(1,"branch"));assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,()->service.requirePublisher(1,"branch","GROWTH"));
        binding.setVerificationMethod("LEGACY_UNVERIFIED");assertEquals(503,assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,()->service.currentPerson(1)).getStatus().value());
    }
}
