package cn.jualn.miniapp.third.cos.dto;

/** Only safe scalar facts leave the provider adapter; no OCR/ASR or signed media URLs. */
public record CosVideoAuditResult(String jobId, String dataId, String objectKey, String state, Decision decision) {
    public enum Decision { PENDING, PASS, REJECT, REVIEW, FAILED }
}
