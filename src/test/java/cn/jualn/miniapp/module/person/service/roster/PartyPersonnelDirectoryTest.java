package cn.jualn.miniapp.module.person.service.roster;

import cn.jualn.miniapp.module.authorization.service.TransactionalAuthorization;
import cn.jualn.miniapp.module.person.entity.ManagedPersonRows;
import cn.jualn.miniapp.module.person.mapper.ManagedPersonMapper;
import cn.jualn.miniapp.module.person.mapper.PersonMapper;
import cn.jualn.miniapp.module.person.service.facts.PersonFactReaders;
import cn.jualn.miniapp.module.person.service.facts.PersonReadContext;
import cn.jualn.miniapp.module.person.service.profile.ManagedPersonAccess;
import cn.jualn.miniapp.module.person.service.profile.ManagedPersonIdentifierService;
import cn.jualn.miniapp.module.person.service.profile.PersonIdentifierNamespaces;
import cn.jualn.miniapp.module.person.service.support.ManagedPersonJournal;
import cn.jualn.miniapp.module.person.service.support.PersonPrivateData;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PartyPersonnelDirectoryTest {

    @Test
    void membersReturnDecryptedStudentNumberAndKeepMaskedProjection() {
        var corrections = mock(PartyPersonnelCorrections.class);
        var readers = mock(PersonFactReaders.class);
        var mapper = mock(PersonMapper.class);
        var access = mock(RosterAccess.class);
        var authorization = mock(TransactionalAuthorization.class);
        var persons = mock(ManagedPersonMapper.class);
        var secrets = new PersonPrivateData(
                Base64.getEncoder().encodeToString(new byte[32]),
                Base64.getEncoder().encodeToString(new byte[32]));
        var identifiers = new ManagedPersonIdentifierService(readers, mapper, persons, mock(ManagedPersonAccess.class),
                mock(ManagedPersonJournal.class), secrets,
                new PersonIdentifierNamespaces("CAMPUS_STUDENT_NUMBER", "学号"));
        var directory = new PartyPersonnelDirectory(corrections, readers, mapper, access, authorization, persons, identifiers);

        var context = mock(PersonReadContext.class);
        var at = LocalDateTime.of(2026, 11, 17, 0, 0);
        when(context.evaluatedAt()).thenReturn(at);
        when(readers.open(7L)).thenReturn(context);
        when(mapper.corruptOrganizationMembers(any())).thenReturn(0L);
        when(mapper.partyMemberCount(any())).thenReturn(1L);
        when(mapper.partyMembers(any())).thenReturn(List.of(
                new PartyPersonnelDirectory.MemberRow("person", "membership", "organization", "张同学")));
        when(readers.identity("person", context)).thenReturn(new PersonFactReaders.IdentityFact(context,
                PersonFactReaders.Outcome.KNOWN, null, "RECORDED", "FULL_PARTY_MEMBER", "ACTIVE",
                null, null, "1", "test"));
        when(corrections.editable(7L, "organization", "person", context)).thenReturn(List.of());

        var number = new ManagedPersonRows.Identifier();
        number.setId("identifier");
        number.setNamespace("CAMPUS_STUDENT_NUMBER");
        number.setStatus("ACTIVE");
        number.setVerification("VERIFIED");
        number.setValueMasked("***");
        number.setPrivateValue(secrets.encrypt("person-identifier:identifier",
                "001234".getBytes(StandardCharsets.UTF_8)));
        when(persons.identifiers("person")).thenReturn(List.of(number));

        var member = directory.members(7L, "organization", 1, 20, null).items().get(0);

        assertEquals("***", member.studentNumberMasked());
        assertEquals("001234", member.studentNumber());
    }
}
