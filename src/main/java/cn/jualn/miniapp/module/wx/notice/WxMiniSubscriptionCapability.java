package cn.jualn.miniapp.module.wx.notice;

import org.springframework.stereotype.Component;

/** Explicit missing integration, not an environment switch or a synthetic per-user grant. */
@Component
public class WxMiniSubscriptionCapability {
    public enum State { CONTRACT_NOT_DEFINED }
    public State state() { return State.CONTRACT_NOT_DEFINED; }
    public boolean canSend() {
        // A future accepted Contracts workflow must supply real scoped eligibility, atomic
        // consumption and revocation/UNKNOWN handling here. Never enable by changing a flag.
        return switch (state()) { case CONTRACT_NOT_DEFINED -> false; };
    }
}
