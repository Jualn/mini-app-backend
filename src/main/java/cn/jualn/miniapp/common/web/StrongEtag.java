package cn.jualn.miniapp.common.web;

import cn.jualn.miniapp.common.exception.ContractProblemException;

/** Strong resource ETags used by contract writes. */
public final class StrongEtag {
    private StrongEtag() {
    }

    public static String of(String resource, long id, long version) {
        return "\"" + resource + "-" + id + "-v" + version + "\"";
    }

    public static void require(String supplied, String resource, long id, long version) {
        if (supplied == null || supplied.isBlank()) {
            throw ContractProblemException.preconditionRequired();
        }
        String normalized = supplied.trim();
        String expected = of(resource, id, version);
        if ("*".equals(normalized) || !expected.equals(normalized)) {
            throw ContractProblemException.preconditionFailed(
                    "etag-check resource=" + resource + ", id=" + id + ", expected=" + expected
                            + ", supplied=" + normalized);
        }
    }
}
