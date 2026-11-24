package cn.jualn.miniapp.module.media.service;

import cn.jualn.miniapp.module.media.controller.MediaContentController;
import cn.jualn.miniapp.module.media.bo.MediaContentBO;
import cn.jualn.miniapp.third.cos.client.CosClient;
import com.qcloud.cos.model.*;
import java.io.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MediaContentTransportTest {
    @Test void rangeVariantsAndUnsupportedRangesHaveOneBoundedInterpretation() {
        assertEquals(new MediaContentController.Range(7,9,true),MediaContentController.Range.parse("bytes=-3",10));
        assertEquals(new MediaContentController.Range(4,9,true),MediaContentController.Range.parse("bytes=4-",10));
        assertEquals(new MediaContentController.Range(0,9,true),MediaContentController.Range.parse("bytes=0-999",10));
        for(String invalid:java.util.List.of("bytes=1-2,4-5","bytes=-0","bytes=10-","bytes=6-3","bytes=999999999999999999999999-","items=0-1"))assertThrows(IllegalArgumentException.class,()->MediaContentController.Range.parse(invalid,10));
    }
    @Test void disconnectAbortsAndClosesUpstreamWithoutRetry() throws Exception {
        var service=mock(MediaContentService.class);when(service.require(1,null,false)).thenReturn(new MediaContentBO("post/retained/7/a","image/png",3));
        var cos=mock(CosClient.class);var object=mock(COSObject.class);var input=spy(new COSObjectInputStream(new ByteArrayInputStream(new byte[]{1,2,3}),null));var metadata=new ObjectMetadata();metadata.setContentLength(3);when(object.getObjectContent()).thenReturn(input);when(object.getObjectMetadata()).thenReturn(metadata);when(cos.openObject("post/retained/7/a",0,2)).thenReturn(object);
        var response=new MockHttpServletResponse(){public jakarta.servlet.ServletOutputStream getOutputStream(){return new jakarta.servlet.ServletOutputStream(){public boolean isReady(){return true;}public void setWriteListener(jakarta.servlet.WriteListener l){}public void write(int b)throws IOException{throw new IOException("synthetic disconnected client");}};}};
        assertThrows(IOException.class,()->new MediaContentController(service,cos).read(1,new MockHttpServletRequest("GET","/v1/media/files/1/content"),response));verify(input).abort();verify(input).close();verify(cos,times(1)).openObject("post/retained/7/a",0,2);
    }
    @Test void incompleteUpstreamResponseBecomes502WithoutAppendingAnErrorToImageBytes() throws Exception {
        var service=mock(MediaContentService.class);when(service.require(1,null,false)).thenReturn(new MediaContentBO("post/retained/7/a","image/png",3));var cos=mock(CosClient.class);var object=new COSObject();var metadata=new ObjectMetadata();metadata.setContentLength(3);object.setObjectMetadata(metadata);object.setObjectContent(new ByteArrayInputStream(new byte[]{1}));when(cos.openObject("post/retained/7/a",0,2)).thenReturn(object);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new MediaContentController(service,cos)).setControllerAdvice(new cn.jualn.miniapp.common.exception.GlobalExceptionHandler()).build();
        var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/media/files/1/content")).andReturn().getResponse();assertEquals(502,response.getStatus());assertTrue(response.getContentType().contains("application/problem+json"));assertNull(response.getHeader("Content-Length"));assertTrue(response.getContentAsString().contains("/problems/external-service-error"));assertEquals("no-store",response.getHeader("Cache-Control"));
    }
    @Test void providerFailureDoesNotCommitBinaryHeaders() throws Exception {
        var service=mock(MediaContentService.class);when(service.require(1,null,false)).thenReturn(new MediaContentBO("post/retained/7/a","image/png",3));var cos=mock(CosClient.class);when(cos.openObject("post/retained/7/a",0,2)).thenThrow(new IllegalStateException("provider unavailable"));var response=new MockHttpServletResponse();
        assertThrows(IllegalStateException.class,()->new MediaContentController(service,cos).read(1,new MockHttpServletRequest("GET","/v1/media/files/1/content"),response));assertNull(response.getHeader("Content-Length"));assertNull(response.getContentType());assertEquals("no-store",response.getHeader("Cache-Control"));
    }
}
