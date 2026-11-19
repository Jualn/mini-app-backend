package cn.jualn.miniapp.common.result;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 分页响应
 *
 * @param <T>
 */
@Data
@AllArgsConstructor(staticName = "of")
public class PageResult<T> {
    /**
     * Allows a contract-specific feed to add its opaque cursor without changing legacy pages.
     */
    protected PageResult(List<T> list, boolean hasMore, Long nextCursor) {
        this.list = list;
        this.hasMore = hasMore;
        this.nextCursor = nextCursor;
    }

    private List<T> list;
    /**
     * 是否还有下一页（游标分页判断：返回数量 == pageSize）
     */
    private Boolean hasMore;
    /**
     * 游标：下次请求传入此值作为 lastId（取列表最后一条 id）
     */
    private Long nextCursor;

    public static <T> PageResult<T> of(List<T> list, boolean hasMore) {
        // nextCursor 由调用方从 list 最后一条取 id
        return new PageResult<>(list, hasMore, null);
    }

    public static <T> PageResult<T> of(List<T> list, boolean hasMore, Long nextCursor) {
        return new PageResult<>(list, hasMore, nextCursor);
    }

    public static <T> PageResult<T> empty() {
        return new PageResult<>(List.of(), false, null);
    }
}
