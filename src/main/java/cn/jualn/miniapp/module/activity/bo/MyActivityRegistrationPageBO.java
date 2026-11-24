package cn.jualn.miniapp.module.activity.bo;
import java.util.List;
public record MyActivityRegistrationPageBO(List<Item> items, String nextCursor) {
    public record Item(RegisteredActivityBO activity, ActivityRegistrationBO registration) { }
}
