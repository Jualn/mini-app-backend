package cn.jualn.miniapp.module.person;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.exception.GlobalExceptionHandler;
import cn.jualn.miniapp.module.person.controller.PersonProblemAdvice;
import cn.jualn.miniapp.module.person.dto.ManagedPersonRequests;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;

class ManagedPersonBoundaryTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void successfulBindingProvenanceAllowsPersonUseUntilEnded() {
        var mapper=org.mockito.Mockito.mock(cn.jualn.miniapp.module.person.mapper.ManagedPersonMapper.class);
        var scopes=new cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes(
            org.mockito.Mockito.mock(cn.jualn.miniapp.module.person.mapper.PersonBusinessMapper.class),
            org.mockito.Mockito.mock(cn.jualn.miniapp.module.person.mapper.PersonMapper.class),mapper,
            org.mockito.Mockito.mock(cn.jualn.miniapp.module.person.service.facts.PersonCatalog.class));
        var binding=new cn.jualn.miniapp.module.person.entity.ManagedPersonRows.Binding();
        binding.setId("binding");binding.setPersonId("person");binding.setUserId(7L);binding.setStatus("ACTIVE");binding.setVerificationMethod("ROSTER_EXACT_MATCH");binding.setSourceReference("roster");
        org.mockito.Mockito.when(mapper.accountBinding(7)).thenReturn(binding);
        assertEquals("binding",scopes.currentAccountBinding(7).bindingId());
        assertTrue(cn.jualn.miniapp.module.person.service.binding.PersonBindingTrust.trusted(binding));
        binding.setVerificationMethod("ADMIN_MANUAL");
        assertEquals("binding",scopes.currentAccountBinding(7).bindingId());
        binding.setStatus("ENDED");
        assertFalse(cn.jualn.miniapp.module.person.service.binding.PersonBindingTrust.trusted(binding));
    }

    @Test void selfAssociationRequestRejectsOrganizationAndPersonInjection() throws Exception {
        var requests=new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        var type=cn.jualn.miniapp.module.person.bo.QualificationBO.Apply.class;
        assertEquals("Name",requests.readValue("{\"realName\":\"Name\",\"studentNumber\":\"001\"}",type).realName());
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,()->requests.readValue("{\"realName\":\"Name\",\"studentNumber\":\"001\",\"organizationId\":\"org\"}",type));
    }

    @Test void missingRosterCompletenessCannotBecomeAnEmptyCompleteRoster() {
        var mapper=org.mockito.Mockito.mock(cn.jualn.miniapp.module.person.mapper.PersonBusinessMapper.class);
        var facts=org.mockito.Mockito.mock(cn.jualn.miniapp.module.person.mapper.PersonMapper.class);
        var organization=new cn.jualn.miniapp.module.person.entity.PersonRows.Organization();organization.setId("organization");organization.setStatus("ACTIVE");
        org.mockito.Mockito.when(facts.organization("organization")).thenReturn(organization);
        var scopes=new cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes(mapper,facts,
                org.mockito.Mockito.mock(cn.jualn.miniapp.module.person.mapper.ManagedPersonMapper.class),
                org.mockito.Mockito.mock(cn.jualn.miniapp.module.person.service.facts.PersonCatalog.class));
        var failure=assertThrows(ContractProblemException.class,()->scopes.requireRoster("organization",java.time.LocalDateTime.now()));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,failure.getStatus());
        org.mockito.Mockito.verify(mapper,org.mockito.Mockito.never()).currentMembers(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
    }

    @Test void mergePatchPresenceIsNotCollapsedIntoNull() throws Exception {
        var omitted = ManagedPersonRequests.Registration.parse(json.readTree("{\"reason\":\"r\",\"verificationReference\":\"s\"}")).command();
        var cleared = ManagedPersonRequests.Registration.parse(json.readTree("{\"majorClass\":null,\"reason\":\"r\",\"verificationReference\":\"s\"}")).command();
        assertFalse(omitted.present()); assertTrue(cleared.present()); assertNull(omitted.majorClass()); assertNull(cleared.majorClass());
    }
    @Test void closedPersonInputRejectsAccountAndActorInjection() throws Exception {
        var input = json.readTree("{\"realName\":\"N\",\"reason\":\"r\",\"verificationReference\":\"s\",\"userId\":\"1\"}");
        assertThrows(ContractProblemException.class, () -> ManagedPersonRequests.Create.parse(input));
    }
    @Test void nonCorrectionCannotCarryAValueEvenWhenNull() throws Exception {
        var input = json.readTree("{\"action\":\"REVOKE\",\"value\":null,\"reason\":\"r\",\"verificationReference\":\"s\"}");
        assertThrows(ContractProblemException.class, () -> ManagedPersonRequests.IdentifierChange.parse(input));
    }
    @Test void securityOwnerFailureUsesPersonDependencyProblem() {
        var response = new PersonProblemAdvice(new GlobalExceptionHandler()).problem(
                new ContractProblemException(HttpStatus.SERVICE_UNAVAILABLE, "/problems/people-source-unavailable", "Personnel operation cannot be completed"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("/problems/person-foundation-unavailable", response.getBody().getType().toString());
        assertEquals("no-store", response.getHeaders().getCacheControl());
    }
}
