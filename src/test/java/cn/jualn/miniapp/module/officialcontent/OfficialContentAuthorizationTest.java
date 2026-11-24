package cn.jualn.miniapp.module.officialcontent;

import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.authorization.service.RbacAuthorizationReader;

import cn.jualn.miniapp.module.authorization.model.RbacModels.*;
import cn.jualn.miniapp.module.officialcontent.bo.OfficialContentSaveBO;
import cn.jualn.miniapp.module.officialcontent.entity.OfficialContent;
import cn.jualn.miniapp.module.officialcontent.enums.*;
import cn.jualn.miniapp.module.officialcontent.mapper.OfficialContentMapper;
import cn.jualn.miniapp.module.officialcontent.service.impl.OfficialContentServiceImpl;
import cn.jualn.miniapp.module.media.TestMediaService;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.security.AdminContextFixture;
import org.junit.jupiter.api.*;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real dispatcher and owner; in-memory decision/Mapper fixtures, no database or login claim. */
class OfficialContentAuthorizationTest {
    RbacAuthorizationReader reader;OfficialContentMapper mapper;OfficialContentServiceImpl service;
    @BeforeEach void setup() {
        AdminContextFixture.open(9);
        reader=mock(RbacAuthorizationReader.class);mapper=mock(OfficialContentMapper.class);
        service=new OfficialContentServiceImpl(org.mockito.Mockito.mock(cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal.class),new CurrentAuthorization(reader),mapper,mock(TestMediaService.class),mock(TestMediaService.class),mock(cn.jualn.miniapp.module.officialcontent.service.OfficialPublicationWriter.class),mock(cn.jualn.miniapp.module.officialcontent.service.OfficialContentSafetyChecks.class));
        var entity=new OfficialContent();entity.setId(1L);entity.setRevision(1L);entity.setPublishStatus("PUBLISHED");
        entity.setPublishedAt(LocalDateTime.now());entity.setCategory("NEWS");
        when(mapper.lock(1)).thenReturn(entity);when(mapper.replace(any())).thenReturn(1);
    }
    @AfterEach void close(){AdminContextFixture.close();}
    @Test void replacingPublicBodyNeedsPublishInAdditionToEdit() {
        allow("official-content:edit");
        assertThrows(BusinessException.class,()->replace(OfficialContentStatus.PUBLISHED));
        verify(mapper,never()).replace(any());
    }
    @Test void withdrawingPublicBodyNeedsUnpublishInAdditionToEdit() {
        allow("official-content:edit");
        assertThrows(BusinessException.class,()->replace(OfficialContentStatus.UNPUBLISHED));
        verify(mapper,never()).replace(any());
    }
    @Test void withdrawalDoesNotAlsoRequirePermissionToPublish() {
        allow("official-content:edit","official-content:unpublish");
        assertDoesNotThrow(()->replace(OfficialContentStatus.UNPUBLISHED));verify(mapper).replace(any());
    }
    void allow(String... permissions) {
        when(reader.read(9)).thenReturn(new Decision(9,Source.ASSIGNMENT,"v","c",LocalDateTime.now(),true,List.of(),
                List.of(new Grant("a","fixture","OFFICIAL_CONTENT","GLOBAL",List.of(permissions),null))));
    }
    void replace(OfficialContentStatus status) {
        service.replace(1,new OfficialContentSaveBO("title","summary","body","publisher",OfficialContentCategory.NEWS,
                status,List.of(),null),"\"official-content-1-v1\"");
    }
}
