package cn.jualn.miniapp.module.media.vo;
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record AttachmentVO(String attachmentId, String kind, String name, String url, Playback playback,
        String retention, java.time.OffsetDateTime expiresAt, String contentType, Long sizeBytes) {
    public AttachmentVO(String id,String kind,String name,String url,Playback playback,String retention,java.time.OffsetDateTime expiresAt) {this(id,kind,name,url,playback,retention,expiresAt,null,null);}
    public AttachmentVO(String id,String kind,String name,String url) { this(id,kind,name,url,null,null,null); }
    public AttachmentVO(String id,String kind,String name,String url,Playback playback) { this(id,kind,name,url,playback,null,null); }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record Playback(String contentType,Integer width,Integer height,Double durationSeconds) {}
}
