package cn.jualn.miniapp.module.officialcontent.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import cn.jualn.miniapp.common.exception.ContractProblemException;

/** Cursor binds the collection and its filters; it conveys no authorization. */
public final class OfficialContentCursor {
    private OfficialContentCursor() {}
    public static String encode(long id, String context) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((context + ":" + id).getBytes(StandardCharsets.UTF_8));
    }
    public static Long decode(String cursor, String context) {
        if (cursor == null) return null;
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String prefix = context + ":";
            if (!raw.startsWith(prefix)) throw new IllegalArgumentException();
            long id = Long.parseLong(raw.substring(prefix.length()));
            if (id <= 0 || !encode(id, context).equals(cursor)) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException ex) {
            throw ContractProblemException.validation(new ContractProblemException.Violation("query", "#/cursor", "INVALID_VALUE", "Cursor does not match this collection"));
        }
    }
}
