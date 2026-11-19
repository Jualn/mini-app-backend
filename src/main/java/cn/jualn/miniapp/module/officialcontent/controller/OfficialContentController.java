package cn.jualn.miniapp.module.officialcontent.controller;

import cn.jualn.miniapp.module.officialcontent.converter.OfficialContentConverter;
import cn.jualn.miniapp.module.officialcontent.dto.OfficialContentPageQuery;
import cn.jualn.miniapp.module.officialcontent.service.OfficialContentService;
import cn.jualn.miniapp.module.officialcontent.vo.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated @RestController @RequiredArgsConstructor
@RequestMapping("/v1/official-contents")
public class OfficialContentController {
    private final OfficialContentService service;
    private final OfficialContentConverter converter;
    @GetMapping
    public ResponseEntity<OfficialContentPageVO> list(@Valid OfficialContentPageQuery query) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(converter.toPageVO(
                service.list(query.getCategory(), null, query.getCursor(), query.getPageSize(), false)));
    }
    @GetMapping("/{contentId}")
    public ResponseEntity<OfficialContentVO> get(@PathVariable @Positive long contentId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(converter.toVO(service.get(contentId, false), true));
    }
}
