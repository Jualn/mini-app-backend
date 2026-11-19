package cn.jualn.miniapp.module.learningtask;

import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO;
import cn.jualn.miniapp.module.learningtask.controller.LearningTaskSliceController;
import cn.jualn.miniapp.module.learningtask.converter.LearningTaskDraftConverter;
import cn.jualn.miniapp.module.learningtask.converter.LearningTaskSliceConverter;
import cn.jualn.miniapp.module.learningtask.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.LocalDateTime;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LearningTaskPreviewHttpTest {
    @Test void createdReplayedAndReadPreviewHaveTheSameStrongEtag() throws Exception {
        var service=mock(LearningTaskService.class);
        var preview=new LearningTaskBO.Preview(7,1,LearningTaskBO.Purpose.PUBLISH,List.of(2L,5L).stream().map(user->new LearningTaskBO.AudienceMember(LearningTaskBO.Subject.account(user),"AVAILABLE",user,null,false)).toList(),2,0,
                LocalDateTime.of(2026,10,17,22,0),LocalDateTime.of(2026,10,17,22,5),0);
        var result=new LearningTaskBO.Result(null,null,null,preview,0,0);
        when(service.preview(eq(1L),any(LearningTaskBO.PreviewRequest.class),anyString(),anyString())).thenReturn(result);
        when(service.preview(1L,7L)).thenReturn(result);
        var mvc=MockMvcBuilders.standaloneSetup(new LearningTaskSliceController(service,mock(LearningTaskSliceQueries.class),
                mock(LearningTaskProviders.class),new LearningTaskDraftConverter(),new LearningTaskSliceConverter(new LearningTaskDraftConverter()))).build();
        for(int attempt=0;attempt<2;attempt++) {
            mvc.perform(post("/v1/admin/learning-tasks/1/audience-previews").contentType("application/json").accept("application/json")
                    .header("If-Match","\"learning-task-1-v2\"").header("Idempotency-Key","same-request")
                    .content("{\"purpose\":\"PUBLISH\",\"audience\":{\"accounts\":{\"userIds\":[\"2\",\"5\"]}}}"))
                    .andExpect(status().isCreated()).andExpect(header().string("ETag","\"learning-preview-true-0-7-v1\""))
                    .andExpect(header().string("Location","/v1/admin/learning-tasks/1/audience-previews/7"))
                    .andExpect(header().string("Cache-Control","no-store"));
        }
        mvc.perform(get("/v1/admin/learning-tasks/1/audience-previews/7").accept("application/json"))
                .andExpect(status().isOk()).andExpect(header().string("ETag","\"learning-preview-true-0-7-v1\""))
                .andExpect(jsonPath("$.resolvedCount").value(2));
    }
}
