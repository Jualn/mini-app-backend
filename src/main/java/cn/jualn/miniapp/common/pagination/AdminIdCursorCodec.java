package cn.jualn.miniapp.common.pagination;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 管理端基于稳定记录 ID 的不透明游标。
 *
 * <p>排序字段的实际边界值仍由 Mapper 根据 ID 查询，HTTP 调用方不能读取或构造游标内容。</p>
 */
public final class AdminIdCursorCodec {

    private static final String VERSION = "v1";
    private static final String INVALID_CURSOR_MESSAGE = "分页游标无效或与排序方式不匹配";

    private AdminIdCursorCodec() {
    }

    public static String encode(String sort, Long id) {
        if (id == null) {
            return null;
        }
        if (sort == null || sort.isBlank() || id <= 0) {
            throw new IllegalArgumentException("sort and positive id are required");
        }
        String value = String.join("|", VERSION, sort, id.toString());
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    public static Long decode(String encoded, String expectedSort) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", -1);
            if (parts.length != 3
                    || !VERSION.equals(parts[0])
                    || expectedSort == null
                    || !expectedSort.equals(parts[1])) {
                throw new IllegalArgumentException("cursor mismatch");
            }
            long id = Long.parseLong(parts[2]);
            if (id <= 0) {
                throw new IllegalArgumentException("cursor id must be positive");
            }
            return id;
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, INVALID_CURSOR_MESSAGE);
        }
    }
}
