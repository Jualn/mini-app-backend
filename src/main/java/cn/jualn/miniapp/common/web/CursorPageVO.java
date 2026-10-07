package cn.jualn.miniapp.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CursorPageVO<T>(List<T> items, String nextCursor) {
}
