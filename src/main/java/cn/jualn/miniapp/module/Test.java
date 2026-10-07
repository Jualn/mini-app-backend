package cn.jualn.miniapp.module;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@RestController
public class Test {

    private static final Path VUE_DIST =
            Paths.get("D:/Jualn/Chan/admin-web/jualn-admin/dist").toAbsolutePath().normalize();

    @GetMapping({
            "/**",
            "/assets/**"
    })
    public ResponseEntity<Resource> vue(HttpServletRequest request) throws IOException {

        String uri = request.getRequestURI();

        String relativePath;

        // Vue 打包后的静态资源
        if (uri.startsWith("/assets/")) {
            relativePath = uri.substring(1);
        } else if (uri.startsWith("/")) {
            relativePath = uri.substring("/".length());
        } else {
            relativePath = "";
        }

        Path file;

        if (relativePath.isEmpty()) {
            file = VUE_DIST.resolve("index.html");
        } else {
            file = VUE_DIST.resolve(relativePath).normalize();

            // 防止 ../ 访问 dist 外面的文件
            if (!file.startsWith(VUE_DIST)) {
                return ResponseEntity.notFound().build();
            }

            // Vue Router history 模式：
            // 如果不是实际文件，就返回 index.html
            if (!Files.exists(file) || Files.isDirectory(file)) {
                file = VUE_DIST.resolve("index.html");
            }
        }

        if (!Files.exists(file)) {
            return ResponseEntity.notFound().build();
        }

        String contentType = Files.probeContentType(file);

        if (contentType == null) {
            contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .body(new FileSystemResource(file));
    }
}
