package cn.jualn.miniapp.module.post.bo;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

public record PostDictionaryPageBO<T>(List<T> items, @JsonInclude(JsonInclude.Include.NON_NULL) String nextCursor) {
}
