package cn.jualn.miniapp.module.officialcontent.vo;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OfficialContentPageVO(List<OfficialContentVO> items, boolean hasMore, String nextCursor) {}
