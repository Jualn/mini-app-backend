package cn.jualn.miniapp.module.audit.service;
/** Stable machine-readable reason within the existing conflict Problem family. */
public final class ReviewConflict extends RuntimeException {
    private final String conflictCode;
    public ReviewConflict(String code) {super("Content safety review conflict: "+code);this.conflictCode=code;}
    public String getConflictCode() {return conflictCode;}
}
