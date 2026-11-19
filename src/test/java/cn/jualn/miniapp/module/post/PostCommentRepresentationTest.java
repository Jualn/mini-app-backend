package cn.jualn.miniapp.module.post;

import cn.jualn.miniapp.common.result.PageResult;
import cn.jualn.miniapp.config.JacksonConfig;
import cn.jualn.miniapp.module.comment.bo.*;
import cn.jualn.miniapp.module.comment.controller.CommentController;
import cn.jualn.miniapp.module.comment.converter.CommentConverter;
import cn.jualn.miniapp.module.comment.service.*;
import cn.jualn.miniapp.module.media.TestMediaService;
import cn.jualn.miniapp.module.post.bo.*;
import cn.jualn.miniapp.module.post.controller.PostController;
import cn.jualn.miniapp.module.post.converter.PostConverter;
import cn.jualn.miniapp.module.post.service.*;
import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.http.converter.json.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MVC, MapStruct and production JSON configuration; query/identity/media are doubles. */
class PostCommentRepresentationTest {
    MappingJackson2HttpMessageConverter json() {
        return new MappingJackson2HttpMessageConverter(new JacksonConfig().objectMapper(new Jackson2ObjectMapperBuilder()));
    }

    @Test void rootAndReplyPagesPreserveTimesIdsPermissionsAndCursors() throws Exception {
        var queries=mock(CommentQueries.class);
        var converter=Mappers.getMapper(CommentConverter.class);
        var time=LocalDateTime.of(2026,10,30,18,20,5);
        var reply=ReplyItemBO.builder().id(9007199254740993L).parentId(20L).replyToCommentId(21L)
                .createdAt(time).content("reply").liked(true).likeCount(3)
                .author(UserSimpleBO.builder().id(42L).build()).replyPermission(PostWritePermission.allowed()).build();
        var root=CommentItemBO.builder().id(20L).createdAt(time).content("root").replyCount(4)
                .liked(false).likeCount(2).previewReplies(List.of(reply)).replyPermission(PostWritePermission.blocked()).build();
        when(queries.pageComment(any())).thenReturn(PageResult.of(List.of(root),Boolean.TRUE,Long.valueOf(20)));
        when(queries.pageReply(any())).thenReturn(PageResult.of(List.of(reply),Boolean.FALSE,reply.getId()));
        var mvc=MockMvcBuilders.standaloneSetup(new CommentController(mock(CommentService.class), mock(cn.jualn.miniapp.module.comment.service.CommunityCommentService.class), mock(cn.jualn.miniapp.module.post.service.PostPublicAccess.class), queries, mock(TestMediaService.class), converter)).setMessageConverters(json()).build();
        mvc.perform(get("/v1/comment").param("targetType","POST").param("targetId","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.nextCursor").value("20"))
                .andExpect(jsonPath("$.data.list[0].createdAt").value("2026-10-30T18:20:05"))
                .andExpect(jsonPath("$.data.list[0].replyPermission.reason").value("QUALIFICATION_UNAVAILABLE"))
                .andExpect(jsonPath("$.data.list[0].previewReplies[0].id").value("9007199254740993"))
                .andExpect(jsonPath("$.data.list[0].previewReplies[0].replyToCommentId").value("21"))
                .andExpect(jsonPath("$.data.list[0].previewReplies[0].liked").value(true));
        mvc.perform(get("/v1/comment/replies").param("targetType","POST").param("targetId","1").param("parentId","20"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.hasMore").value(false))
                .andExpect(jsonPath("$.data.list[0].createdAt").value("2026-10-30T18:20:05"))
                .andExpect(jsonPath("$.data.list[0].replyPermission.state").value("ALLOWED"))
                .andExpect(jsonPath("$.data.nextCursor").value("9007199254740993"));
        reply.setReplyToCommentId(null);
        mvc.perform(get("/v1/comment/replies").param("targetType","POST").param("targetId","1").param("parentId","20"))
                .andExpect(jsonPath("$.data.list[0].replyToCommentId").doesNotExist());
    }

    @Test void postDetailPreservesViewerFactsTimeAndCacheHeaders() throws Exception {
        var queries=mock(PostQueries.class);
        when(queries.getPostDetail(88L)).thenReturn(PostDetailBO.builder().id(88L).postType("NORMAL")
                .liked(true).publishedAt(LocalDateTime.of(2026,10,30,18,20,5)).topics(List.of())
                .commentPermission(PostWritePermission.allowed()).attachments(List.of()).build());
        var mvc=MockMvcBuilders.standaloneSetup(new PostController(mock(PostService.class), mock(cn.jualn.miniapp.module.post.service.PostCommunityService.class), queries, mock(TestMediaService.class), Mappers.getMapper(PostConverter.class), mock(PostEditingService.class),org.mockito.Mockito.mock(cn.jualn.miniapp.module.post.service.GrowthRecordAccess.class),org.mockito.Mockito.mock(cn.jualn.miniapp.module.post.service.GrowthRecordService.class)))
                .setMessageConverters(json()).build();
        mvc.perform(get("/v1/post/88"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(header().string("Vary","Authorization"))
                .andExpect(jsonPath("$.data.id").value("88")).andExpect(jsonPath("$.data.liked").value(true))
                .andExpect(jsonPath("$.data.publishedAt").value("2026-10-30 18:20:05"))
                .andExpect(jsonPath("$.data.commentPermission.state").value("ALLOWED"));
    }
}
