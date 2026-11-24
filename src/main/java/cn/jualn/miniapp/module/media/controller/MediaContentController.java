package cn.jualn.miniapp.module.media.controller;

import cn.jualn.miniapp.module.media.service.MediaContentService;
import cn.jualn.miniapp.third.cos.client.CosClient;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;

/** Synchronous bounded streaming preserves servlet and upstream resource ownership. */
@RestController @RequiredArgsConstructor
public class MediaContentController {
    private final MediaContentService content;
    private final CosClient cos;
    @RequestMapping(value="/v1/media/files/{fileId}/content",method={RequestMethod.GET,RequestMethod.HEAD})
    public void read(@PathVariable long fileId,HttpServletRequest request,HttpServletResponse response)throws IOException {
        stream(fileId,request.getHeader("Authorization")==null?null:UserContext.getUserId(),false,request,response);
    }
    @RequestMapping(value="/v1/admin/media/files/{fileId}/content",method={RequestMethod.GET,RequestMethod.HEAD})
    public void admin(@PathVariable long fileId,HttpServletRequest request,HttpServletResponse response)throws IOException {
        stream(fileId,AdminStpUtil.STP_LOGIC.getLoginIdAsLong(),true,request,response);
    }
    private void stream(long id,Long actor,boolean admin,HttpServletRequest request,HttpServletResponse response)throws IOException {
        response.setHeader("Cache-Control","no-store");response.setHeader("X-Content-Type-Options","nosniff");
        var file=content.require(id,actor,admin);long size=file.sizeBytes();
        Range range;
        try {range=Range.parse(request.getHeader("Range"),size);}
        catch(IllegalArgumentException invalid){response.setStatus(416);response.setHeader("Content-Range","bytes */"+size);return;}
        if("HEAD".equals(request.getMethod())){headers(response,file.contentType(),range,size);return;}
        var object=cos.openObject(file.objectKey(),range.start(),range.end());
        try(var input=object.getObjectContent()) {
            long remaining=range.end()-range.start()+1;
            try {
                if(object.getObjectMetadata().getContentLength()!=remaining)throw new IOException("Unexpected storage response length");
                headers(response,file.contentType(),range,size);
                byte[] buffer=new byte[32768];
                while(remaining>0){int count=input.read(buffer,0,(int)Math.min(buffer.length,remaining));if(count<0)throw new IOException("Truncated storage response");response.getOutputStream().write(buffer,0,count);remaining-=count;}
            } finally {if(remaining>0)input.abort();}
        }
    }
    private void headers(HttpServletResponse response,String contentType,Range range,long size) {
        response.setContentType(contentType);response.setHeader("Accept-Ranges","bytes");
        response.setHeader("Content-Disposition","inline");response.setContentLengthLong(range.end()-range.start()+1);
        if(range.partial()){response.setStatus(206);response.setHeader("Content-Range","bytes "+range.start()+"-"+range.end()+"/"+size);}
    }
    @ExceptionHandler(IOException.class)
    public org.springframework.http.ResponseEntity<org.springframework.http.ProblemDetail> interrupted(IOException failure,HttpServletResponse response) {
        // A committed partial response cannot be replaced with JSON. Content-Length still signals truncation.
        if(response.isCommitted())return null;
        response.reset();
        var problem=org.springframework.http.ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatus.BAD_GATEWAY,"Media transfer interrupted");
        problem.setType(java.net.URI.create("/problems/external-service-error"));
        return org.springframework.http.ResponseEntity.status(502).contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON).cacheControl(org.springframework.http.CacheControl.noStore()).body(problem);
    }
    public record Range(long start,long end,boolean partial) {
        public static Range parse(String value,long size) {
            if(value==null)return new Range(0,size-1,false);
            if(!value.matches("bytes=([0-9]+-[0-9]*|-[0-9]+)"))throw new IllegalArgumentException();
            String[] parts=value.substring(6).split("-",-1);
            try {
                long start,end;
                if(parts[0].isEmpty()){long suffix=Long.parseLong(parts[1]);if(suffix<=0)throw new IllegalArgumentException();start=Math.max(0,size-suffix);end=size-1;}
                else {start=Long.parseLong(parts[0]);end=parts[1].isEmpty()?size-1:Math.min(size-1,Long.parseLong(parts[1]));}
                if(start<0 || start>=size || end<start)throw new IllegalArgumentException();return new Range(start,end,true);
            }catch(NumberFormatException invalid){throw new IllegalArgumentException(invalid);}
        }
    }
}
