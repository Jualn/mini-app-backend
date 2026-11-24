package cn.jualn.miniapp.module.media.bo;

/** Authorized immutable transport facts, without storage lifecycle or business entities. */
public record MediaContentBO(String objectKey,String contentType,long sizeBytes) {}
