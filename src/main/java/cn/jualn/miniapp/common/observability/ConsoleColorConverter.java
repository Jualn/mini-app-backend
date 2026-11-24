package cn.jualn.miniapp.common.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.springframework.boot.ansi.AnsiColor;
import org.springframework.boot.ansi.AnsiElement;
import org.springframework.boot.ansi.AnsiStyle;
import org.springframework.boot.logging.logback.ColorConverter;

/**
 * Palette override only; Boot still owns ANSI detection, switches and fixed-color options.
 */
public final class ConsoleColorConverter extends ColorConverter {
    @Override
    protected String transform(ILoggingEvent event, String text) {
        if (getFirstOption() != null) return super.transform(event, text);
        AnsiElement color = switch (event.getLevel().toInt()) {
            case Level.ERROR_INT -> AnsiColor.BRIGHT_RED;
            case Level.WARN_INT -> AnsiColor.BRIGHT_YELLOW;
            case Level.INFO_INT -> AnsiColor.BRIGHT_BLUE;
//            case Level.DEBUG_INT -> AnsiColor.BLUE;
            default -> AnsiStyle.FAINT;
        };
        return toAnsiString(text, color);
    }
}
