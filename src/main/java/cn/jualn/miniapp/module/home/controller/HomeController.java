package cn.jualn.miniapp.module.home.controller;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.web.AdminQuery;
import cn.jualn.miniapp.module.home.model.HomeContent.*;
import cn.jualn.miniapp.module.home.service.HomeBannerService;
import cn.jualn.miniapp.module.home.service.HomeSummaryService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class HomeController {
    public static final String SUMMARY = "/v1/home/summary";
    public static final String BANNERS = "/v1/admin/home/banners";
    public static final String OPTIONS = "/v1/admin/home/banner-options";
    public static final String AUTHENTICATED_ACTOR = HomeController.class.getName() + ".actor";
    public static final String REQUEST_STARTED = HomeController.class.getName() + ".requestStarted";

    private final HomeBannerService banners;
    private final HomeSummaryService summaries;

    @GetMapping(SUMMARY)
    public ResponseEntity<Summary> getHomeSummary(HttpServletRequest request) {
        Object started = request.getAttribute(REQUEST_STARTED);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(summaries.get(started instanceof Long time ? time : System.nanoTime()));
    }

    @GetMapping(BANNERS)
    public ResponseEntity<Configuration> getAdminHomeBanners() {
        return admin(() -> response(banners.get(actor())));
    }

    @PutMapping(BANNERS)
    public ResponseEntity<Configuration> replaceAdminHomeBanners(
            HttpServletRequest request, @RequestBody JsonNode body) {
        var tags = java.util.Collections.list(request.getHeaders(HttpHeaders.IF_MATCH));
        String ifMatch = tags.isEmpty() ? null : String.join(", ", tags);
        return admin(() -> response(banners.replace(actor(), ifMatch, body)));
    }

    @GetMapping(OPTIONS)
    public ResponseEntity<OptionPage> listAdminHomeBannerOptions(@RequestParam Type targetType,
                                                                 @RequestParam(required = false) String q, @RequestParam(required = false) String targetId,
                                                                 @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(admin(() -> banners.options(actor(), targetType, q, targetId, page, pageSize)));
    }

    private static <T> T admin(java.util.function.Supplier<T> action) {
        try {
            return AdminQuery.read(action);
        } catch (TransactionException failure) {
            throw AdminQuery.unavailable();
        }
    }

    private static long actor() {
        return UserContext.getUserId();
    }

    private static ResponseEntity<Configuration> response(HomeBannerService.Versioned value) {
        return ResponseEntity.ok().eTag(value.etag()).cacheControl(CacheControl.noStore()).body(value.configuration());
    }
}
