package cn.jualn.miniapp.module.activity.bo;
import java.util.List;
public record ActivityRegistrationPageBO(List<ActivityRegistrationBO> items, boolean hasMore, String nextCursor) {}
