package cn.jualn.miniapp.module.post.bo;

public record PostVersionedBO<T>(T value, String etag) {
}
