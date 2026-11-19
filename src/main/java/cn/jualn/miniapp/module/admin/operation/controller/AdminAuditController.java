package cn.jualn.miniapp.module.admin.operation.controller;
import cn.jualn.miniapp.module.admin.operation.service.AdminAuditQueries;
import cn.jualn.miniapp.module.admin.operation.bo.AdminAuditBO.*;
import cn.jualn.miniapp.module.admin.auth.service.AdminTokenService;
import java.time.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
public class AdminAuditController {
    private final AdminTokenService tokens;
    private final AdminAuditQueries queries;
    @GetMapping(value="/v1/admin/authorization/duty-history",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Page> duties(@RequestParam String occurredAfter,@RequestParam String occurredBefore,
        @RequestParam(required=false) String personId,@RequestParam(required=false) String userId,@RequestParam(required=false) String source,
        @RequestParam(defaultValue="20") int pageSize,@RequestParam(required=false) String cursor) {
        long actor=actor();return ok(queries.page(new Query("DUTY",actor,time(occurredAfter),time(occurredBefore),personId,userId,source,null,null,null,null,null,pageSize),cursor));
    }
    @GetMapping(value="/v1/admin/operation-audits",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Page> operations(@RequestParam String occurredAfter,@RequestParam String occurredBefore,
        @RequestParam(required=false) String actorUserId,@RequestParam(required=false) String operationId,
        @RequestParam(required=false) String targetType,@RequestParam(required=false) String targetId,@RequestParam(required=false) String result,
        @RequestParam(defaultValue="20") int pageSize,@RequestParam(required=false) String cursor) {
        long actor=actor();return ok(queries.page(new Query("OPERATION",actor,time(occurredAfter),time(occurredBefore),null,null,null,actorUserId,operationId,targetType,targetId,result,pageSize),cursor));
    }
    private long actor() {long actor=AdminAuditHttpAccess.authenticate(tokens);queries.root(actor);return actor;}
    private LocalDateTime time(String value) {
        try {return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();}
        catch(java.time.format.DateTimeParseException failure) {throw AdminAuditQueries.invalid();}
    }
    private ResponseEntity<Page> ok(Page value) {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
}
