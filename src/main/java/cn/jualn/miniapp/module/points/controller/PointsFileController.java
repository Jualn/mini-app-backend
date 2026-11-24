package cn.jualn.miniapp.module.points.controller;

import cn.jualn.miniapp.module.media.bo.PointMaterialBO;
import cn.jualn.miniapp.module.points.bo.PointsBO.Action;
import cn.jualn.miniapp.module.points.dto.PointsRequests;
import cn.jualn.miniapp.module.points.service.PointsDependencies;
import cn.jualn.miniapp.module.points.service.PointsPrivateMaterials;
import cn.jualn.miniapp.module.points.service.PointsService;
import cn.jualn.miniapp.module.points.vo.PointFileVO;

import java.net.URI;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import static cn.jualn.miniapp.module.points.PointsProblems.forbidden;

@RestController
@RequiredArgsConstructor
public class PointsFileController {
    private final PointsService points;
    private final PointsDependencies dependencies;
    private final PointsPrivateMaterials materials;

    @PostMapping("/v1/users/me/points/file-upload-credentials")
    public ResponseEntity<PointFileVO.Credentials> upload(@RequestBody PointsRequests.Upload body, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        long user = dependencies.actor(Action.CREATE);
        PointsService.validateKey(key);
        if (!points.eligibility().eligible()) throw forbidden("ineligible");
        var declaration = body.files().stream().map(f -> new PointMaterialBO.Declaration(f.fileName(), f.kind(), f.contentType(), f.sizeBytes())).toList();
        return ok(PointFileVO.credentials(materials.upload(user, declaration, key)));
    }

    @PostMapping("/v1/users/me/points/files")
    public ResponseEntity<PointFileVO.File> register(@RequestBody PointsRequests.Register body, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        long user = dependencies.actor(Action.MY_READ);
        PointsService.validateKey(key);
        var result = materials.register(user, body.uploadId(), body.fileIndex(), key);
        return ResponseEntity.created(URI.create("/v1/users/me/points/files/" + result.fileId())).cacheControl(CacheControl.noStore()).body(PointFileVO.file(result));
    }

    @GetMapping("/v1/users/me/points/files/{fileId}")
    public ResponseEntity<PointFileVO.File> file(@PathVariable String fileId) {
        return ok(PointFileVO.file(points.myFile(fileId)));
    }

    @GetMapping("/v1/users/me/points/files/{fileId}/access")
    public ResponseEntity<PointFileVO.Access> access(@PathVariable String fileId) {
        return ok(PointFileVO.access(points.myFileAccess(fileId)));
    }

    @GetMapping("/v1/admin/points/files/{fileId}/access")
    public ResponseEntity<PointFileVO.Access> review(@PathVariable String fileId) {
        long actor = dependencies.actor(Action.MATERIAL_READ);
        var file = materials.bound(fileId);
        return ok(PointFileVO.access(points.reviewFileAccess(actor, fileId, file.applicationId(), file.ownerUserId())));
    }

    private static <T> ResponseEntity<T> ok(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
