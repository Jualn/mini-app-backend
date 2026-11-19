package cn.jualn.miniapp.common.web;

import cn.jualn.miniapp.common.exception.GlobalExceptionHandler;
import cn.jualn.miniapp.common.result.PageResult;
import cn.jualn.miniapp.module.search.controller.SearchController;
import cn.jualn.miniapp.module.search.converter.SearchConverter;
import cn.jualn.miniapp.module.search.service.SearchService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ActivitySearchRetirementMvcTest {
    @Test
    void retiredActivityRouteIsAbsentAndPostSearchStillWorks() throws Exception {
        var service = mock(SearchService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new SearchController(service, mock(SearchConverter.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/v1/search/activities").param("keyword", "robot")).andExpect(status().isNotFound());
        verifyNoInteractions(service);
        when(service.searchPosts(any())).thenReturn(PageResult.of(List.of(), false));
        mvc.perform(get("/v1/search/posts").param("keyword", "robot")).andExpect(status().isOk());
        verify(service).searchPosts(any());
    }
}
