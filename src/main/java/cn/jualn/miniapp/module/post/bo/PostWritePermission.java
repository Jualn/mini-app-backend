package cn.jualn.miniapp.module.post.bo;

public record PostWritePermission(String state, String reason) {
    public static PostWritePermission allowed() {
        return new PostWritePermission("ALLOWED", "NONE");
    }

    public static PostWritePermission denied(String reason) {
        return new PostWritePermission("DENIED", reason);
    }

    public static PostWritePermission blocked() {
        return new PostWritePermission("BLOCKED", "QUALIFICATION_UNAVAILABLE");
    }
}
