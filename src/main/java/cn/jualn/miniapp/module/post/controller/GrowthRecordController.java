package cn.jualn.miniapp.module.post.controller;

import cn.jualn.miniapp.module.post.service.GrowthRecordService;
import cn.jualn.miniapp.module.post.bo.GrowthRecordBO;
import cn.jualn.miniapp.module.post.dto.request.GrowthRecordRequest;
import cn.jualn.miniapp.module.media.bo.GrowthMediaBO;
import cn.jualn.miniapp.module.post.PostProblems;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.net.URI;

@RestController @RequiredArgsConstructor
public class GrowthRecordController {
    private final GrowthRecordService records;
    @GetMapping("/v1/users/me/growth-publishing-context") public ResponseEntity<?> context(){return response(records.context());}
    @GetMapping("/v1/users/me/growth-records") public ResponseEntity<?> mine(@RequestParam(required=false) String recordType,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int pageSize){return response(records.page(true,null,null,recordType,cursor,pageSize));}
    @GetMapping("/v1/growth-records") public ResponseEntity<?> list(@RequestParam(required=false) String organizationId,@RequestParam(required=false) String personId,@RequestParam(required=false) String recordType,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int pageSize){return response(records.page(false,personId,organizationId,recordType,cursor,pageSize));}
    @GetMapping("/v1/growth-records/{postId}") public ResponseEntity<?> get(@PathVariable long postId){return response(records.get(postId));}
    @PostMapping("/v1/growth-records") public ResponseEntity<?> create(@RequestBody GrowthRecordRequest.Create request,@RequestHeader(value="Idempotency-Key",required=false) String key){
        var result=records.create(new GrowthRecordBO.Create(request.organizationId(),request.recordType(),request.title(),request.content(),request.mediaIds(),request.categoryId(),request.topicIds()),key);
        return ResponseEntity.created(URI.create("/v1/growth-records/"+result.link().postId())).cacheControl(CacheControl.noStore()).varyBy("Authorization").body(result);
    }
    @PostMapping("/v1/growth-media/uploads") public ResponseEntity<?> upload(@RequestBody GrowthRecordRequest.Upload request){return created(records.upload(new GrowthMediaBO.UploadCommand(request.organizationId(),request.recordType(),request.files(),request.postId())));}
    @PostMapping("/v1/growth-media") public ResponseEntity<?> register(@RequestBody GrowthRecordRequest.Register request){if(request.fileIndex()==null)throw PostProblems.invalid("fileIndex","REQUIRED","fileIndex required");return created(records.register(request.uploadId(),request.fileIndex()));}
    private ResponseEntity<?> created(Object result){return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).varyBy("Authorization").body(result);}
    private ResponseEntity<?> response(Object result){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).varyBy("Authorization").body(result);}
}
