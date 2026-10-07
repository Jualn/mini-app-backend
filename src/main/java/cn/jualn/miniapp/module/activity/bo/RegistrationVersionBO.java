package cn.jualn.miniapp.module.activity.bo;

/** Identity and revision observed by the caller; independent of HTTP header syntax. */
public record RegistrationVersionBO(long registrationId, long version) {
}
