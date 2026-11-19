package cn.jualn.miniapp.common.observability;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;
import org.springframework.boot.logging.StackTracePrinter;

import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Logback/Boot rendering hook: retain failure locations, never exception messages or SQL.
 */
public final class SafeStackTracePrinter extends ThrowableProxyConverter implements StackTracePrinter {
    private static final int MAX_LENGTH = 8192;
    private static final int MAX_THROWABLES = 16;
    private static final int MAX_FRAMES = 48;

    @Override
    public String convert(ILoggingEvent event) {
        return render(event.getThrowableProxy());
    }

    @Override
    public void printStackTrace(Throwable throwable, Appendable out) throws IOException {
        out.append(render(new ThrowableProxy(throwable)));
    }

    private static String render(IThrowableProxy proxy) {
        StringBuilder out = new StringBuilder();
        Set<IThrowableProxy> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        append(proxy, "", out, seen);
        if (out.length() > MAX_LENGTH) {
            out.setLength(MAX_LENGTH);
            out.append(" [truncated]");
        }
        return out.toString();
    }

    private static void append(IThrowableProxy proxy, String relation, StringBuilder out,
                               Set<IThrowableProxy> seen) {
        if (proxy == null || out.length() >= MAX_LENGTH) return;
        if (seen.size() >= MAX_THROWABLES || !seen.add(proxy)) {
            out.append("[throwable chain truncated]\n");
            return;
        }
        out.append(relation).append(proxy.getClassName()).append(" [message omitted]\n");
        StackTraceElementProxy[] frames = proxy.getStackTraceElementProxyArray();
        if (frames != null) {
            int uniqueFrames = Math.max(0, frames.length - proxy.getCommonFrames());
            for (int i = 0; i < Math.min(uniqueFrames, MAX_FRAMES) && out.length() < MAX_LENGTH; i++) {
                out.append("\tat ").append(frames[i].getStackTraceElement()).append('\n');
            }
            if (uniqueFrames > MAX_FRAMES) out.append("\t[frames truncated]\n");
            if (proxy.getCommonFrames() > 0) out.append("\t[common frames omitted]\n");
        }
        append(proxy.getCause(), "Caused by: ", out, seen);
        IThrowableProxy[] suppressed = proxy.getSuppressed();
        if (suppressed != null) {
            for (IThrowableProxy item : suppressed) {
                if (seen.size() >= MAX_THROWABLES || out.length() >= MAX_LENGTH) break;
                append(item, "Suppressed: ", out, seen);
            }
        }
    }
}
