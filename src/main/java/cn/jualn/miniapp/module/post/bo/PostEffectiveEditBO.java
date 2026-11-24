package cn.jualn.miniapp.module.post.bo;

public record PostEffectiveEditBO(long id, String title, String content, Long categoryId, long revision,
                                  boolean checked) {
}
