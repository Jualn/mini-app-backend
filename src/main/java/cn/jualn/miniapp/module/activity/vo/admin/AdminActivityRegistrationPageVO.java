package cn.jualn.miniapp.module.activity.vo.admin;
import java.util.List;
public record AdminActivityRegistrationPageVO(List<AdminActivityRegistrationVO> items, boolean hasMore, String nextCursor) {}
