package cn.jualn.miniapp.module.activity.bo;

/** Result distinguishes first creation (201) from restoration (200). */
public record ActivityRegistrationWriteBO(ActivityRegistrationBO registration, boolean created) {
}
