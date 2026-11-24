package cn.jualn.miniapp.module.learningtask;
import cn.jualn.miniapp.module.learningtask.service.*;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.*;
import cn.jualn.miniapp.module.authorization.model.UnifiedModels.Target;
import cn.jualn.miniapp.module.person.service.access.*;
import cn.jualn.miniapp.module.person.service.facts.PersonCatalog;
import org.junit.jupiter.api.*;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class LearningTaskRecipientOptionsTest {
    final LearningTaskAuthorizationPolicy authorization=mock(LearningTaskAuthorizationPolicy.class);
    final LearningTaskScopePolicy scopes=mock(LearningTaskScopePolicy.class);
    final PersonBusinessScopes persons=mock(PersonBusinessScopes.class);
    final PersonCatalog catalog=mock(PersonCatalog.class);
    final LearningTaskResponsibilities responsibilities=mock(LearningTaskResponsibilities.class);
    final LearningRecipientReader reader=mock(LearningRecipientReader.class);
    final Target target=new Target("ORGANIZATION",null,"org-a");
    LearningTaskRecipientOptions options;
    @BeforeEach void setup() {
        options=new LearningTaskRecipientOptions(authorization,scopes,persons,catalog,responsibilities,reader);
        when(authorization.authorize(any(),any(),any())).thenReturn(9L);
        when(scopes.optionTarget(9L,42L,null,null)).thenReturn(target);when(scopes.optionCeilings(9,target,false)).thenReturn(List.of(target));
        when(scopes.articleOptionsVersion(9,42L,null,null)).thenReturn("current-authority-v1");
        when(persons.now()).thenReturn(LocalDateTime.of(2026,11,2,12,0));
        when(persons.organizationName("org-a")).thenReturn("可见组织");
        when(catalog.value()).thenReturn(new PersonCatalog.Catalog("fixture","CONFIRMED",List.of()));
    }
    @Test void minimalNamesStayInsideAudienceCeilingAndUnavailableOptionsCannotBeSelected() throws Exception {
        when(reader.candidates(anyBoolean(),anyList(),anyList(),anyList(),any(),any(),anyInt(),any())).thenReturn(List.of(new LearningRecipientReader.Item("p1","p1","同名","专业A班"),new LearningRecipientReader.Item("p2","p2",null),new LearningRecipientReader.Item("outside","outside","不应披露")));
        when(scopes.optionCovered("PERSON","p1",List.of(target))).thenReturn(true);when(scopes.optionCovered("PERSON","p2",List.of(target))).thenReturn(true);
        when(persons.positiveOrganizations(anyString(),any())).thenReturn(List.of("org-a","private-org"));
        when(responsibilities.recipient(Subject.person("p1"))).thenReturn(new Recipient(Subject.person("p1"),42L,"b1","AVAILABLE"));
        when(responsibilities.recipient(Subject.person("p2"))).thenReturn(new Recipient(Subject.person("p2"),null,null,"NO_BINDING"));
        var result=options.list(42L,null,null,"PERSON",null,List.of(),List.of(),null,20);
        assertEquals(2,result.items().size());assertTrue(result.items().get(0).selectable());
        assertEquals(List.of(new LearningTaskRecipientOptions.Organization("org-a","可见组织")),result.items().get(0).display().organizations());
        assertNull(result.items().get(0).display().developmentStage());assertFalse(result.items().get(1).selectable());assertEquals("NAME_UNAVAILABLE",result.items().get(1).unavailableReason());
        assertEquals("专业A班",result.items().get(0).display().disambiguation());
        verify(persons,never()).organizationName("private-org");verify(authorization).authorize(Action.READ,42L,null);verify(authorization,never()).authorize(Action.EDIT,42L,null);
        var json=new com.fasterxml.jackson.databind.ObjectMapper();json.writerWithDefaultPrettyPrinter().writeValue(java.nio.file.Path.of("target/learning-task-v1-recipient-wire.json").toFile(),List.of(Map.of("schema","LearningTaskRecipientOptionPage","status",200,"producer","Owning service with explicit policy and Person doubles","body",json.valueToTree(result))));
    }
    @Test void invisibleOrganizationAndDirectoryFiltersAreRejectedBeforeDirectoryRead() {
        assertThrows(RuntimeException.class,()->options.list(42L,null,null,"PERSON",null,List.of("private-org"),List.of(),null,20));
        assertThrows(RuntimeException.class,()->options.list(42L,null,null,"ORGANIZATION",null,List.of("org-a"),List.of(),null,20));
        verifyNoInteractions(reader);
    }
    @Test void cursorIsBoundToCurrentAuthorityAndQuery() {
        when(reader.candidates(anyBoolean(),anyList(),anyList(),anyList(),any(),any(),anyInt(),any())).thenReturn(List.of(new LearningRecipientReader.Item("p1",null,"one"),new LearningRecipientReader.Item("p2",null,"two")));
        var first=options.list(42L,null,null,"PERSON",null,List.of(),List.of(),null,1);assertNotNull(first.nextCursor());
        when(scopes.articleOptionsVersion(9,42L,null,null)).thenReturn("authority-revoked-v2");
        assertThrows(RuntimeException.class,()->options.list(42L,null,null,"PERSON",null,List.of(),List.of(),first.nextCursor(),1));
    }

    @Test void selectableFilteringFillsThePageAfterExcludedAndUnavailableRows() {
        when(reader.candidates(anyBoolean(),anyList(),anyList(),anyList(),any(),isNull(),anyInt(),any()))
                .thenReturn(List.of(new LearningRecipientReader.Item("p1",null,"one"),new LearningRecipientReader.Item("p2",null,"two")));
        when(reader.candidates(anyBoolean(),anyList(),anyList(),anyList(),any(),eq("p1"),anyInt(),any()))
                .thenReturn(List.of(new LearningRecipientReader.Item("p2",null,"two"),new LearningRecipientReader.Item("p3",null,"three")));
        when(reader.candidates(anyBoolean(),anyList(),anyList(),anyList(),any(),eq("p2"),anyInt(),any()))
                .thenReturn(List.of(new LearningRecipientReader.Item("p3",null,"three")));
        when(scopes.optionCovered(eq("PERSON"),anyString(),anyList())).thenReturn(true);
        when(responsibilities.recipient(Subject.person("p2"))).thenReturn(new Recipient(Subject.person("p2"),null,null,"NO_BINDING"));
        when(responsibilities.recipient(Subject.person("p3"))).thenReturn(new Recipient(Subject.person("p3"),3L,"b3","AVAILABLE"));
        var result=options.list(42L,null,null,"PERSON",null,List.of(),List.of(),null,1,true,List.of("p1"),false);
        assertEquals(List.of("p3"),result.items().stream().map(LearningTaskRecipientOptions.Option::id).toList());
        assertNull(result.nextCursor());
        assertTrue(result.fixedSelectionSupported());
        verify(responsibilities,never()).recipient(Subject.person("p1"));
    }

    @Test void organizationSelectionRequiresCompleteRosterAndKeepsUnavailableMembersVisible() {
        when(reader.candidates(anyBoolean(),anyList(),anyList(),anyList(),any(),any(),anyInt(),any()))
                .thenReturn(List.of(new LearningRecipientReader.Item("p1","p1","未绑定人员")));
        when(scopes.optionCovered(eq("PERSON"),anyString(),anyList())).thenReturn(true);
        when(persons.positiveOrganizations("p1",persons.now())).thenReturn(List.of("org-a"));
        when(responsibilities.recipient(Subject.person("p1"))).thenReturn(new Recipient(Subject.person("p1"),null,null,"NO_BINDING"));
        var result=options.list(42L,null,null,"PERSON",null,List.of("org-a"),List.of(),null,20,false,List.of(),true);
        assertFalse(result.items().get(0).selectable());
        assertEquals("NO_BINDING",result.items().get(0).unavailableReason());
        verify(persons,times(2)).requireRoster(eq("org-a"),any());
        assertThrows(RuntimeException.class,()->options.list(42L,null,null,"PERSON",null,List.of("org-a"),List.of(),null,20,true,List.of(),true));
    }
}
