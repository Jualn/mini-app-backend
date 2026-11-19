package cn.jualn.miniapp.module.content.service.impl;

import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.authorization.service.RbacAuthorizationReader;

import cn.dev33.satoken.SaManager;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.module.comment.service.CommentGovernanceService;
import cn.jualn.miniapp.module.content.mapper.AdminContentMapper;
import cn.jualn.miniapp.module.media.TestMediaService;
import cn.jualn.miniapp.module.post.service.PostGovernanceService;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import static cn.jualn.miniapp.module.authorization.model.RbacModels.*;
import static org.mockito.Mockito.*;

class AdminContentOwnerScopeTest {
    final RbacAuthorizationReader reader=mock(RbacAuthorizationReader.class);
    final AdminContentMapper mapper=mock(AdminContentMapper.class);
    final cn.jualn.miniapp.module.post.service.PostOrganizationService organization=mock(cn.jualn.miniapp.module.post.service.PostOrganizationService.class);
    final AdminContentServiceImpl service=new AdminContentServiceImpl(new CurrentAuthorization(reader), mapper, mock(cn.jualn.miniapp.module.post.service.PostManagementQueries.class), mock(PostGovernanceService.class), mock(CommentGovernanceService.class), mock(TestMediaService.class), organization, mock(cn.jualn.miniapp.module.content.service.AdminQaContextReader.class));
    @BeforeEach void session() {
        var storage=mock(cn.dev33.satoken.context.model.SaStorage.class);Map<String,Object> values=new HashMap<>();
        when(storage.get(anyString())).thenAnswer(i->values.get(i.getArgument(0)));
        when(storage.set(anyString(),any())).thenAnswer(i->{values.put(i.getArgument(0),i.getArgument(1));return storage;});
        when(storage.delete(anyString())).thenAnswer(i->{values.remove(i.getArgument(0));return storage;});
        SaManager.getSaTokenContext().setContext(mock(cn.dev33.satoken.context.model.SaRequest.class),mock(cn.dev33.satoken.context.model.SaResponse.class),storage);
        AdminStpUtil.STP_LOGIC.switchTo(9L);
    }
    @AfterEach void close(){SaManager.getSaTokenContext().clearContext();}
    @Test void ownerScopeReachesBothPageAndSummaryWithoutInventingAnArticleConsumer() {
        var root=new Grant("root","ROOT","AUTHORIZATION","GLOBAL",List.of("authorization:root-manage"),null);
        var caps=List.of(new OwnerCapability("root","POST","GLOBAL",List.of("content:read")),
                new OwnerCapability("root","ARTICLE","GLOBAL",List.of("content:read")),
                new OwnerCapability("unrelated","ACTIVITY","GLOBAL",List.of("content:read")));
        when(reader.read(9)).thenReturn(new Decision(9,Source.ASSIGNMENT,"v","c",LocalDateTime.now(),true,List.of(),List.of(root),caps));
        service.pageContents(null);
        verify(mapper).selectPage(isNull(),isNull(),isNull(),isNull(),isNull(),eq("latest"),isNull(),isNull(),isNull(),isNull(),isNull(),isNull(),isNull(),eq(21),eq(List.of("POST")));
        verify(mapper).selectScopedSummary(null,List.of("POST"));
        verify(mapper,never()).selectSummary();
    }
    @Test void ordinaryRoleScopesArePreservedWithoutAddingRootPowers() {
        var grant=new Grant("ordinary","CONTENT_OPERATOR","OFFICIAL_CONTENT","GLOBAL",List.of("content:read"),null);
        when(reader.read(9)).thenReturn(new Decision(9,Source.ASSIGNMENT,"v","c",LocalDateTime.now(),true,List.of(),List.of(grant)));
        service.pageContents(null);
        verify(mapper).selectScopedSummary(null,List.of("OFFICIAL_CONTENT"));
        verify(mapper).selectPage(isNull(),isNull(),isNull(),isNull(),isNull(),eq("latest"),isNull(),isNull(),isNull(),isNull(),isNull(),isNull(),isNull(),eq(21),eq(List.of("OFFICIAL_CONTENT")));
    }
    void legacyReader() {when(reader.read(9)).thenReturn(new Decision(9,Source.LEGACY,"v","c",LocalDateTime.now(),true,List.of("*"),List.of()));}
    @Test void newFiltersRequireExplicitPostAndUnknownDictionaryUsesQueryValidation() {
        legacyReader();
        var missingType=cn.jualn.miniapp.module.content.bo.AdminContentQueryBO.builder().postType("NORMAL").build();
        var missing=org.junit.jupiter.api.Assertions.assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,()->service.pageContents(missingType));
        org.junit.jupiter.api.Assertions.assertEquals("/problems/validation-error",missing.getType());
        doThrow(cn.jualn.miniapp.module.post.PostProblems.problem(org.springframework.http.HttpStatus.BAD_REQUEST,"post-category-invalid","unknown"))
            .when(organization).validateFilter(999L,null);
        var unknown=org.junit.jupiter.api.Assertions.assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,
            ()->service.pageContents(cn.jualn.miniapp.module.content.bo.AdminContentQueryBO.builder().type("post").categoryId(999L).build()));
        org.junit.jupiter.api.Assertions.assertEquals("/problems/validation-error",unknown.getType());
        org.junit.jupiter.api.Assertions.assertEquals("query",unknown.getErrors().get(0).in());verifyNoInteractions(mapper);
    }
    @Test void cursorCannotBeReusedAfterChangingAFilter() {
        legacyReader();var row=new cn.jualn.miniapp.module.content.mapper.AdminContentListRow();row.setId(1L);row.setType("comment");row.setUpdatedAt(LocalDateTime.now());
        when(mapper.selectPage(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any())).thenReturn(List.of(row,row));
        var first=service.pageContents(cn.jualn.miniapp.module.content.bo.AdminContentQueryBO.builder().keyword("first").pageSize(1).build());
        clearInvocations(mapper);
        var failure=org.junit.jupiter.api.Assertions.assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,
            ()->service.pageContents(cn.jualn.miniapp.module.content.bo.AdminContentQueryBO.builder().keyword("different").pageSize(1).cursor(first.getNextCursor()).build()));
        org.junit.jupiter.api.Assertions.assertEquals("/problems/invalid-cursor",failure.getType());verifyNoInteractions(mapper);
    }

}
