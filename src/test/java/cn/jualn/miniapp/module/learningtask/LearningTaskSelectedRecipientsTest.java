package cn.jualn.miniapp.module.learningtask;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.authorization.service.RbacCursorCodec;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.*;
import cn.jualn.miniapp.module.learningtask.entity.LearningTask;
import cn.jualn.miniapp.module.learningtask.mapper.LearningTaskMapper;
import cn.jualn.miniapp.module.learningtask.service.*;
import cn.jualn.miniapp.module.person.service.access.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LearningTaskSelectedRecipientsTest {
    @Test void preservesRedactedPositionsAndRejectsCursorAfterVersionChange() throws Exception {
        var authorization = mock(LearningTaskAuthorizationPolicy.class);
        var scopes = mock(LearningTaskScopePolicy.class);
        var tasks = mock(LearningTaskMapper.class);
        var responsibilities = mock(LearningTaskResponsibilities.class);
        var persons = mock(PersonBusinessScopes.class);
        var names = mock(LearningRecipientReader.class);
        var json = new ObjectMapper();
        var service = new LearningTaskSelectedRecipients(authorization, scopes, tasks, responsibilities,
                persons, names, new RbacCursorCodec(), json);
        var task = new LearningTask();
        task.setRevision(3L);
        task.setAudience(json.writeValueAsString(new Audience(List.of("z-person", "hidden-person", "z-person"),
                List.of("42"), List.of(new Filter(List.of("ignored-org"), List.of(), List.of())), "ignored-scope")));
        when(tasks.selectById(12L)).thenReturn(task);
        when(authorization.authorize(Action.READ, 12L, null)).thenReturn(9L);
        when(scopes.audienceVersion(9L, 12L)).thenReturn("current");
        var account = Subject.account(42);
        var person = Subject.person("z-person");
        when(scopes.selectedRecipientCovered(eq(account), anyList())).thenReturn(true);
        when(scopes.selectedRecipientCovered(eq(person), anyList())).thenReturn(true);
        when(responsibilities.recipient(account)).thenReturn(new Recipient(account, 42L, null, "AVAILABLE"));
        when(responsibilities.recipient(person)).thenReturn(new Recipient(person, null, null, "NO_BINDING"));
        when(names.selectedNames(true, List.of("42"))).thenReturn(List.of(new LearningRecipientReader.Item("42", null, "Account")));

        var first = service.list(12L, null, 2);
        assertEquals(account, first.items().get(0).subject());
        assertEquals("NAMED", first.items().get(0).status());
        assertEquals("{\"status\":\"REDACTED\"}", json.writeValueAsString(first.items().get(1)));
        verify(responsibilities, never()).recipient(Subject.person("hidden-person"));
        String cursorBody = new String(Base64.getUrlDecoder().decode(first.nextCursor().split("\\.")[0]), StandardCharsets.UTF_8);
        assertFalse(cursorBody.contains("hidden-person"));

        var next = service.list(12L, first.nextCursor(), 2);
        assertEquals(List.of(new LearningTaskSelectedRecipients.Item("NAME_UNAVAILABLE", person, "NO_BINDING", null, null)), next.items());
        assertNull(next.nextCursor());
        assertEquals("/problems/invalid-cursor", assertThrows(ContractProblemException.class,
                () -> service.list(12L, first.nextCursor(), 1)).getType());
        task.setRevision(4L);
        assertEquals("/problems/invalid-cursor", assertThrows(ContractProblemException.class,
                () -> service.list(12L, first.nextCursor(), 2)).getType());
    }
}
