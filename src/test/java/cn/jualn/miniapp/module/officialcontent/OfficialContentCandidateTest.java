package cn.jualn.miniapp.module.officialcontent;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import cn.jualn.miniapp.module.media.TestMediaService;
import cn.jualn.miniapp.module.officialcontent.bo.OfficialContentSaveBO;
import cn.jualn.miniapp.module.officialcontent.enums.*;
import cn.jualn.miniapp.module.officialcontent.mapper.OfficialContentMapper;
import cn.jualn.miniapp.module.officialcontent.service.*;
import cn.jualn.miniapp.module.officialcontent.service.impl.OfficialContentServiceImpl;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class OfficialContentCandidateTest {
 @Test void expiredImageAndLinkVideoUseCandidateConflictBeforeWritingBusinessData() {
  var media=mock(TestMediaService.class);var mapper=mock(OfficialContentMapper.class);
  var service=new OfficialContentServiceImpl(org.mockito.Mockito.mock(cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal.class),mock(cn.jualn.miniapp.module.authorization.service.CurrentAuthorization.class),mapper,media,media,mock(OfficialPublicationWriter.class),mock(OfficialContentSafetyChecks.class));
  cn.jualn.miniapp.security.AdminContextFixture.open(9);
  try {
   for(String kind:List.of("IMAGE","LINK")) {
    var value=MediaAttachmentBO.builder().id(11L).kind(kind).retention("CANDIDATE").expiresAt(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(1)).verified(true).build();
    when(media.batchGetAttachments(List.of(11L))).thenReturn(Map.of(11L,value));
    var command=new OfficialContentSaveBO("title","summary","body","publisher",OfficialContentCategory.values()[0],OfficialContentStatus.DRAFT,"IMAGE".equals(kind)?List.of(11L):List.of(),"LINK".equals(kind)?11L:null);
    assertEquals("/problems/attachment-candidate-expired",assertThrows(ContractProblemException.class,()->service.create(command)).getType());
   }
   verifyNoInteractions(mapper);
  } finally {cn.jualn.miniapp.security.AdminContextFixture.close();}
 }
}
