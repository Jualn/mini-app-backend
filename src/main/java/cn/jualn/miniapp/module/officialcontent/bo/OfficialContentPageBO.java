package cn.jualn.miniapp.module.officialcontent.bo;
import java.util.List;
public record OfficialContentPageBO(List<OfficialContentBO> items, boolean hasMore, String nextCursor) {}
