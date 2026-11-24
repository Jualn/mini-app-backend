package cn.jualn.miniapp.module.learningtask;

import cn.jualn.miniapp.common.exception.GlobalExceptionHandler;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Audience;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.FileKind;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Requirements;
import cn.jualn.miniapp.module.learningtask.controller.LearningTaskController;
import cn.jualn.miniapp.module.learningtask.controller.LearningTaskProblemAdvice;
import cn.jualn.miniapp.module.learningtask.service.LearningTaskEntryGate;
import cn.jualn.miniapp.module.learningtask.service.UnavailableLearningTaskProviders;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.Dependency;
import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.unavailable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LearningTaskHttpGateTest {
    @Test void everyCanonicalOperationFailsClosedWhenItsAuthorityIsUnavailable() throws Exception {
        Path source=Path.of("../contracts/api/paths/learning-task.yaml");
        assertTrue(Files.exists(source),"Canonical sibling Contracts checkout required for route coverage");
        var json=new ObjectMapper().addMixIn(ProblemDetail.class,ProblemDetailJacksonMixin.class);
        var fileService=mock(cn.jualn.miniapp.module.learningtask.service.LearningTaskFiles.class, invocation -> {throw unavailable(Dependency.AUTHORIZATION);});
        var options=mock(cn.jualn.miniapp.module.learningtask.service.LearningTaskRecipientOptions.class, invocation -> {throw unavailable(Dependency.AUTHORIZATION);});
        var errors=new GlobalExceptionHandler();
        var providers=mock(cn.jualn.miniapp.module.learningtask.service.LearningTaskProviders.class);
        org.mockito.Mockito.when(providers.authorize(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenThrow(unavailable(Dependency.AUTHORIZATION));
        var converter=new cn.jualn.miniapp.module.learningtask.converter.LearningTaskDraftConverter();
        var articleOptions=mock(cn.jualn.miniapp.module.learningtask.service.LearningTaskArticleOptions.class, invocation->{throw unavailable(Dependency.AUTHORIZATION);});
        var selected=mock(cn.jualn.miniapp.module.learningtask.service.LearningTaskSelectedRecipients.class, invocation->{throw unavailable(Dependency.AUTHORIZATION);});
        var mvc=MockMvcBuilders.standaloneSetup(new cn.jualn.miniapp.module.learningtask.controller.LearningTaskSelectedRecipientsController(selected,converter),new LearningTaskController(fileService,new cn.jualn.miniapp.module.learningtask.converter.LearningTaskDraftConverter()),new cn.jualn.miniapp.module.learningtask.controller.LearningTaskOptionsController(options),new cn.jualn.miniapp.module.learningtask.controller.LearningTaskArticleOptionsController(articleOptions),new cn.jualn.miniapp.module.learningtask.controller.LearningTaskDraftController(mock(cn.jualn.miniapp.module.learningtask.service.LearningTaskService.class),providers,converter),
                new cn.jualn.miniapp.module.learningtask.controller.LearningTaskSliceController(mock(cn.jualn.miniapp.module.learningtask.service.LearningTaskService.class),
                        mock(cn.jualn.miniapp.module.learningtask.service.LearningTaskSliceQueries.class),providers,converter,new cn.jualn.miniapp.module.learningtask.converter.LearningTaskSliceConverter(converter)),
                new cn.jualn.miniapp.module.learningtask.controller.LearningTaskSubmissionController(mock(cn.jualn.miniapp.module.learningtask.service.LearningTaskService.class),providers,
                        mock(cn.jualn.miniapp.module.learningtask.service.LearningTaskSliceQueries.class),mock(cn.jualn.miniapp.module.learningtask.service.LearningTaskSubmissionQueries.class),converter,
                        new cn.jualn.miniapp.module.learningtask.converter.LearningTaskSliceConverter(converter)))
                .setControllerAdvice(new LearningTaskProblemAdvice(errors),errors)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
        var paths=json.readTree(Files.readString(source)); int covered=0;
        for(var fields=paths.fields();fields.hasNext();) {
            var path=fields.next(); String url=path.getKey().replaceAll("\\{[^}]+}", "42");
            for(var operations=path.getValue().fields();operations.hasNext();) {
                var operation=operations.next();
                if(!operation.getValue().has("operationId")) { continue; }
                mvc.perform(request(HttpMethod.valueOf(operation.getKey().toUpperCase(Locale.ROOT)),url)
                        .contentType("application/json").queryParam("type","PERSON").content(url.endsWith("/files")?"{\"uploadId\":\"example\",\"fileIndex\":0}":"{}"))
                    .andExpect(result -> assertEquals(503,result.getResponse().getStatus(),operation.getKey()+" "+url))
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(header().string("Cache-Control","no-store"))
                    .andExpect(jsonPath("$.type").value("/problems/learning-task-dependency-unavailable"))
                    .andExpect(jsonPath("$.dependency").value("AUTHORIZATION"));
                covered++;
            }
        }
        assertTrue(covered>=30);
    }
    @Test void unavailableProductionPortsNeverReturnEmptyPeopleSafeFilesOrAcceptedNotifications() {
        var provider=new UnavailableLearningTaskProviders(mock(cn.jualn.miniapp.module.article.service.ArticleReferenceReadService.class),mock(cn.jualn.miniapp.module.learningtask.service.LearningTaskAuthorizationPolicy.class),
                (actor,text)->{throw unavailable(Dependency.CONTENT_SAFETY);},mock(cn.jualn.miniapp.module.user.service.UserLearningTaskRecipientService.class),mock(cn.jualn.miniapp.module.learningtask.service.LearningTaskNotifications.class));
        assertEquals("AUTHORIZATION",assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,
                ()->provider.resolve(9,new Audience(List.of(),List.of("42"),null,"unavailable-scope"))).getMessage());
        assertEquals("PRIVATE_MEDIA",assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,
                ()->provider.bindPrivateFiles(42,1,1,List.of(1L),new Requirements(false,1,0,1,List.of(FileKind.PDF)))).getMessage());
        assertEquals("CONTENT_SAFETY",assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,
                ()->provider.textSafety(9,"材料")).getMessage());
        // Notification acceptance is now a production integration; missing private media still has no fallback.
    }
}
