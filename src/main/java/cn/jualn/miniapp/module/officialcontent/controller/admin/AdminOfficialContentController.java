package cn.jualn.miniapp.module.officialcontent.controller.admin;

import cn.jualn.miniapp.common.web.StrongEtag;
import cn.jualn.miniapp.module.officialcontent.bo.OfficialContentBO;
import cn.jualn.miniapp.module.officialcontent.converter.OfficialContentConverter;
import cn.jualn.miniapp.module.officialcontent.dto.admin.*;
import cn.jualn.miniapp.module.officialcontent.service.OfficialContentService;
import cn.jualn.miniapp.module.officialcontent.vo.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated @RestController @RequiredArgsConstructor
@RequestMapping("/v1/admin/official-contents")
public class AdminOfficialContentController {
    private final OfficialContentService service;
    private final OfficialContentConverter converter;
    @GetMapping
    public ResponseEntity<OfficialContentPageVO> list(@Valid AdminOfficialContentPageQuery query) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(converter.toPageVO(
                service.list(query.getCategory(), query.getPublishStatus(), query.getCursor(), query.getPageSize(), true)));
    }
    @GetMapping("/{contentId}")
    public ResponseEntity<OfficialContentVO> get(@PathVariable @Positive long contentId) {
        return response(service.get(contentId, true));
    }
    @PostMapping
    public ResponseEntity<OfficialContentVO> create(@Valid @RequestBody OfficialContentSaveRequest request) {
        var value = service.create(converter.toSaveBO(request));
        return ResponseEntity.created(URI.create("/v1/admin/official-contents/" + value.id())).cacheControl(CacheControl.noStore())
                .eTag(StrongEtag.of("official-content", value.id(), value.revision())).body(converter.toVO(value, true));
    }
    @PutMapping("/{contentId}")
    public ResponseEntity<OfficialContentVO> replace(@PathVariable @Positive long contentId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody OfficialContentSaveRequest request) {
        return response(service.replace(contentId, converter.toSaveBO(request), ifMatch));
    }
    @PostMapping("/{contentId}:publish")
    public ResponseEntity<OfficialContentVO> publish(@PathVariable @Positive long contentId,
            @RequestHeader(value=HttpHeaders.IF_MATCH,required=false) String ifMatch,@RequestBody(required=false) String body) {
        if(body!=null && !body.isBlank()) throw cn.jualn.miniapp.common.exception.ContractProblemException.validation(
            new cn.jualn.miniapp.common.exception.ContractProblemException.Violation("body","#","INVALID_VALUE","Publication commands do not accept a body"));
        return response(service.publication(contentId,true,ifMatch));
    }
    @PostMapping("/{contentId}:unpublish")
    public ResponseEntity<OfficialContentVO> unpublish(@PathVariable @Positive long contentId,
            @RequestHeader(value=HttpHeaders.IF_MATCH,required=false) String ifMatch,@RequestBody(required=false) String body) {
        if(body!=null && !body.isBlank()) throw cn.jualn.miniapp.common.exception.ContractProblemException.validation(
            new cn.jualn.miniapp.common.exception.ContractProblemException.Violation("body","#","INVALID_VALUE","Publication commands do not accept a body"));
        return response(service.publication(contentId,false,ifMatch));
    }
    private ResponseEntity<OfficialContentVO> response(OfficialContentBO value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(StrongEtag.of("official-content", value.id(), value.revision()))
                .body(converter.toVO(value, true));
    }
}
