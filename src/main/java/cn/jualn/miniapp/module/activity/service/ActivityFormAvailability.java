package cn.jualn.miniapp.module.activity.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Keep publication and submission gated until all clients have passed integration acceptance. */
@Component
public class ActivityFormAvailability {
    private final boolean enabled;
    public ActivityFormAvailability(@Value("${activity.registration.enabled:false}") boolean enabled) { this.enabled = enabled; }
    public boolean isEnabled() { return enabled; }
    public void requireEnabled() { ActivityFormPolicy.require(enabled, "平台表单尚未开放"); }
    public String registrationStatus(int publication, Integer mode, java.time.LocalDateTime start, Integer startPrecision,
            java.time.LocalDateTime end, Integer endPrecision, java.time.LocalDateTime now) {
        if (!Integer.valueOf(2).equals(mode) && !Integer.valueOf(4).equals(mode))
            return cn.jualn.miniapp.module.eventcontent.service.EventTimePolicy.registration(publication, mode, start, startPrecision, end, endPrecision, now);
        if (!enabled || publication != 1) return "UNAVAILABLE";
        if (end == null || !Integer.valueOf(2).equals(endPrecision)) return "UNAVAILABLE";
        if (!now.isBefore(end)) return "CLOSED";
        if (start != null && now.isBefore(start)) return "UPCOMING";
        return "OPEN";
    }
}
