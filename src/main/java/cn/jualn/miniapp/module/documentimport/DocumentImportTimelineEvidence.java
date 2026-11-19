package cn.jualn.miniapp.module.documentimport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Bounded temporal evidence interpretation with explicit, traceable business policies. */
final class DocumentImportTimelineEvidence {
    // Trusted business policy, matching TimelineSchedulePolicy and the canonical calendar zone.
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern DATE = Pattern.compile(
            "(?<![0-9])(?:(\\d{4})\\s*年\\s*)?(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日"
                    + "|(?<![0-9])(\\d{4})-(\\d{2})-(\\d{2})(?![0-9])");
    private static final Pattern CONTEXT_YEAR = Pattern.compile(
            "(?:〔|\\[)(20\\d{2})(?:〕|\\])|(?m:^\\s*)(20\\d{2})\\s*年(?=\\s*\\d{1,2}\\s*月)"
                    + "|(?:通知日期|落款|发布时间|比赛时间|活动时间|考试时间)[：:\\s]{0,8}(20\\d{2})\\s*年"
                    + "|(20\\d{2})\\s*年[^。；;\\n]{0,16}(?:活动|事项|比赛|大赛|考试|通知)");
    private static final Pattern CLOCK = Pattern.compile(
            "(?<![0-9:+])(?:(上午|下午|晚上|晚间|中午|早上|凌晨)\\s*)?"
                    + "([0-2]?\\d|[零〇一二两三四五六七八九十]{1,3})"
                    + "(?:\\s*[:：]\\s*(\\d{2})(?:\\s*[:：]\\s*(\\d{2})(?:\\.([0-9]{1,9}))?)?"
                    + "|\\s*[点时](半|[零〇一二两三四五六七八九十]{1,3}分|\\d{1,2}分)?)");
    private static final Pattern PERIOD = Pattern.compile("上午|下午|晚上|晚间|中午|早上|凌晨");
    private static final Pattern UNCERTAIN = Pattern.compile("暂定|待定|预计|拟定|左右|前后|日期另行通知|时间另行通知");
    private static final Pattern OFFSET = Pattern.compile("[+-](?:0\\d|1[0-8]):[0-5]\\d|(?<=[0-9])Z");
    private static final Pattern START = Pattern.compile(
            "(?:公共事项|活动|事项|比赛|讲座|会议|运动会|选拔赛|大赛|考试)[^。；;]{0,48}(?:开始|开幕|启动|开场)");
    private final String source;
    private final List<DateMention> mentions = new ArrayList<>();
    private final Set<Integer> years = new LinkedHashSet<>();
    private final List<String> yearSources = new ArrayList<>();
    private final int businessYear;

    DocumentImportTimelineEvidence(String source) {
        this(source, java.time.Clock.system(BUSINESS_ZONE));
    }

    DocumentImportTimelineEvidence(String source, java.time.Clock clock) {
        this.source = source;
        businessYear = LocalDate.now(clock.withZone(BUSINESS_ZONE)).getYear();
        var context = CONTEXT_YEAR.matcher(source);
        while (context.find()) {
            if (Pattern.compile("出生|毕业|往届|获奖").matcher(source.substring(context.start(),
                    Math.min(source.length(), context.end() + 24))).find()) continue;
            for (int group = 1; group <= 4; group++) if (context.group(group) != null) {
                years.add(Integer.parseInt(context.group(group)));
                yearSources.add("SOURCE[" + context.start() + "," + context.end() + "):" + context.group());
            }
        }
        var date = DATE.matcher(source);
        while (date.find()) {
            boolean iso = date.group(4) != null;
            Integer year = iso ? Integer.valueOf(date.group(4)) : date.group(1) == null ? null : Integer.valueOf(date.group(1));
            mentions.add(new DateMention(year, Integer.parseInt(date.group(iso ? 5 : 2)),
                    Integer.parseInt(date.group(iso ? 6 : 3)), date.start(), date.end(), date.group()));
        }
    }

    void validate(ArrayNode timeline, List<String> excerpts, Consumer<TimeEvidence> trace, Consumer<String> warn) {
        for (int index = 0; index < timeline.size(); index++) {
            ObjectNode node = (ObjectNode) timeline.get(index);
            ObjectNode schedule = (ObjectNode) node.path("schedule");
            boolean start = Set.of("ACTIVITY_START", "PUBLIC_EVENT_START").contains(node.path("type").asText());
            String kind = schedule.path("kind").asText();
            if ("TEXT".equals(kind)) {
                if (start && excerpts.stream().noneMatch(this::supportsStart)) fail(index, "原文缺少主体开始语义");
                for (DateMention mention : mentions) {
                    if (!cited(excerpts, mention.text()) || !sameMonthDay(schedule.path("timeDescription").asText(), mention)) continue;
                    String fragment = fragment(mention);
                    if (clocks(fragment).stream().anyMatch(Clock::review)) {
                        warn.accept("timeline[" + index + "]：原文“上午12:00”有歧义，中午12:00仅为建议解释，需人工核对；保留 TEXT，不产生机器时刻。");
                        trace.accept(new TimeEvidence(index, "timeDescription", schedule.path("timeDescription").asText(),
                                mention.text(), yearBasis(mention), "上午12:00", "NOT_APPLIED", "TEXT", true));
                        break;
                    }
                }
                continue;
            }
            boolean review = false;
            String original = null;
            DateMention rangeStart = null;
            DateMention rangeEnd = null;
            Clock clockStart = null;
            Clock clockEnd = null;
            for (String field : List.of("date", "startDate", "endDate", "time", "startTime", "endTime")) {
                if (!schedule.has(field)) continue;
                String machine = schedule.path(field).asText();
                OffsetDateTime exact = kind.startsWith("EXACT") ? OffsetDateTime.parse(machine) : null;
                LocalDate expected = exact == null ? LocalDate.parse(machine) : exact.toLocalDate();
                TimeEvidence accepted = null;
                for (DateMention mention : mentions) {
                    if (mention.month() != expected.getMonthValue() || mention.day() != expected.getDayOfMonth()
                            || !cited(excerpts, mention.text())) continue;
                    Integer year = mention.year() != null ? mention.year() : years.size() == 1 ? years.iterator().next()
                            : years.isEmpty() ? businessYear : null;
                    if (year == null || year != expected.getYear()) continue;
                    String fragment = fragment(mention);
                    if (start && (!supportsStart(context(mention, fragment)) || excerpts.stream().noneMatch(this::supportsStart))) continue;
                    // Conditional/approximate arrangements cannot become a confirmed machine schedule.
                    if (UNCERTAIN.matcher(fragment.replace("时间另行通知", exact == null ? "" : "时间另行通知")).find()) continue;
                    if (fragment.replaceAll("\\s+", "").contains("另行通知")
                            && !node.path("description").asText().replaceAll("\\s+", "").contains("另行通知")) continue;
                    if (exact == null) {
                        List<Clock> sourceClocks = clocks(fragment);
                        boolean ambiguous = sourceClocks.stream().anyMatch(Clock::review);
                        if (ambiguous) {
                            if (sourceClocks.stream().filter(Clock::review).anyMatch(clock -> !cited(excerpts, clock.text())
                                    || clock.period() != null && !cited(excerpts, clock.period()))) continue;
                            review = true;
                            original = originalTime(mention, fragment, sourceClocks);
                        }
                        accepted = new TimeEvidence(index, field, machine, mention.text(), yearBasis(mention),
                                ambiguous ? "上午12:00" : "NOT_APPLIED", "NOT_APPLIED", "DATE", ambiguous);
                        if (field.equals("startDate")) rangeStart = mention;
                        if (field.equals("endDate")) rangeEnd = mention;
                        break;
                    }
                    List<Clock> supportedClocks = clocks(fragment);
                    for (Clock clock : supportedClocks) {
                        if (start && field.equals("time") && supportedClocks.size() > 1 && !clock.equals(supportedClocks.get(0))) continue;
                        if (!cited(excerpts, clock.text()) || !clock.supports(exact.toLocalTime())
                                || clock.period() != null && !cited(excerpts, clock.period())) continue;
                        String zone = zoneBasis(fragment, exact);
                        if (zone == null) continue;
                        accepted = new TimeEvidence(index, field, machine, mention.text(), yearBasis(mention), clock.text(),
                                zone, clock.precision(), clock.review());
                        review |= clock.review();
                        original = originalTime(mention, fragment, supportedClocks);
                        if (field.equals("startTime")) { rangeStart = mention; clockStart = clock; }
                        if (field.equals("endTime")) { rangeEnd = mention; clockEnd = clock; }
                        break;
                    }
                    if (accepted != null) break;
                }
                if (accepted == null) fail(index, "日期/年份上下文、对应钟点、真实精度、时区或开始语义缺少一致来源；候选整体拒绝");
                trace.accept(accepted);
            }
            if (kind.endsWith("RANGE") && !linkedRange(rangeStart, rangeEnd, clockStart, clockEnd)) {
                fail(index, "区间边界不是原文中同一明确范围；候选整体拒绝");
            }
            if (review) {
                if (kind.endsWith("RANGE")) {
                    List<Clock> endClocks = clocks(fragment(rangeEnd));
                    int endOffset = clockEnd != null ? clockEnd.end() : endClocks.isEmpty() ? 0 : endClocks.get(endClocks.size() - 1).end();
                    original = source.substring(rangeStart.start(), rangeEnd.end() + endOffset);
                }
                if (original.length() > 450) fail(index, "待核对原文超出 TEXT 表达范围；候选整体拒绝");
                // No public uncertainty flag exists; TEXT cannot feed precise reminder scheduling.
                schedule.removeAll();
                schedule.put("kind", "TEXT");
                schedule.put("timeDescription", original.strip() + "（上午12:00有歧义，中午12:00解释需人工核对）");
                warn.accept("timeline[" + index + "]：原文“上午12:00”有歧义，已保留原话并降为 TEXT；中午解释需人工核对。");
            }
        }
    }

    private boolean linkedRange(DateMention start, DateMention end, Clock startClock, Clock endClock) {
        if (start == null || end == null) return false;
        int left = start.end() + (startClock == null ? 0 : startClock.end());
        int right = endClock != null && start.equals(end) ? end.end() + endClock.start() : end.start();
        if (right <= left) return false;
        String bridge = source.substring(left, right);
        return bridge.matches("\\s*(?:Z|[+-][0-9]{2}:[0-9]{2})?\\s*[—–\\-~～至到]\\s*");
    }

    private String originalTime(DateMention mention, String fragment, List<Clock> clocks) {
        int end = clocks.get(clocks.size() - 1).end();
        var deadline = Pattern.compile("^\\s*(?:之前|以前|前|截止)").matcher(fragment.substring(end));
        return mention.text() + fragment.substring(0, end + (deadline.find() ? deadline.end() : 0));
    }

    private String fragment(DateMention mention) {
        int end = Math.min(source.length(), mention.end() + 160);
        for (DateMention next : mentions) if (next.start() >= mention.end()) { end = Math.min(end, next.start()); break; }
        for (int i = mention.end(); i < end; i++) if ("。；;".indexOf(source.charAt(i)) >= 0) { end = i + 1; break; }
        return source.substring(mention.end(), end);
    }

    private String context(DateMention mention, String fragment) {
        int start = Math.max(0, mention.start() - 48);
        for (int i = start; i < mention.start(); i++) if ("。；;".indexOf(source.charAt(i)) >= 0) start = i + 1;
        return source.substring(start, mention.end()) + fragment;
    }

    private boolean supportsStart(String text) {
        var matcher = START.matcher(text);
        while (matcher.find()) if (!Pattern.compile("报名|材料|提交|截止|发布").matcher(matcher.group()).find()) return true;
        return false;
    }

    private String yearBasis(DateMention mention) {
        return mention.year() != null ? "SOURCE_EXPLICIT:" + mention.text()
                : years.size() == 1 ? "DOCUMENT_CONTEXT:" + String.join(" | ", yearSources)
                : years.isEmpty() ? "SYSTEM_CURRENT_BUSINESS_YEAR:Asia/Shanghai:" + businessYear : "UNRESOLVED_OR_CONFLICTING";
    }

    private boolean cited(List<String> excerpts, String token) { return excerpts.stream().anyMatch(e -> e.contains(token)); }

    private boolean sameMonthDay(String text, DateMention expected) {
        var date = DATE.matcher(text);
        while (date.find()) {
            boolean iso = date.group(4) != null;
            if (Integer.parseInt(date.group(iso ? 5 : 2)) == expected.month()
                    && Integer.parseInt(date.group(iso ? 6 : 3)) == expected.day()) return true;
        }
        return false;
    }

    private String zoneBasis(String fragment, OffsetDateTime expected) {
        var offsets = OFFSET.matcher(fragment);
        Set<String> explicit = new LinkedHashSet<>();
        while (offsets.find()) if (explicitOffset(fragment, offsets.start(), offsets.group())) {
            explicit.add(java.time.ZoneOffset.of(offsets.group()).toString());
        }
        if (!explicit.isEmpty()) return explicit.size() == 1 && explicit.contains(expected.getOffset().toString())
                ? "SOURCE_EXPLICIT:" + explicit.iterator().next() : null;
        if (fragment.contains("UTC") || fragment.contains("GMT")) return null;
        var configured = BUSINESS_ZONE.getRules().getValidOffsets(expected.toLocalDateTime());
        return configured.size() == 1 && configured.get(0).equals(expected.getOffset())
                ? "SYSTEM_BUSINESS_ZONE:" + BUSINESS_ZONE.getId() : null;
    }

    private List<Clock> clocks(String text) {
        List<Clock> result = new ArrayList<>();
        var matcher = CLOCK.matcher(text);
        while (matcher.find()) {
            if (matcher.start() > 0 && text.charAt(matcher.start() - 1) == '-'
                    && explicitOffset(text, matcher.start() - 1, "-" + matcher.group())) continue;
            String suffix = text.substring(matcher.end(), Math.min(text.length(), matcher.end() + 3));
            if (suffix.matches("^(一刻|三刻|多|左右|前后).*")) continue;
            String period = matcher.group(1);
            if (period == null) {
                var hint = PERIOD.matcher(text.substring(0, matcher.start()));
                while (hint.find()) period = hint.group();
            }
            int hour = number(matcher.group(2));
            int originalHour = hour;
            if (("上午".equals(period) || "早上".equals(period)) && hour > 12) continue;
            int minute = matcher.group(3) != null ? Integer.parseInt(matcher.group(3))
                    : "半".equals(matcher.group(6)) ? 30 : matcher.group(6) == null ? 0
                    : number(matcher.group(6).replace("分", ""));
            int second = matcher.group(4) == null ? 0 : Integer.parseInt(matcher.group(4));
            int nano = matcher.group(5) == null ? 0 : Integer.parseInt((matcher.group(5) + "000000000").substring(0, 9));
            boolean numericNoon = hour == 12 && matcher.group(3) != null;
            boolean review = "上午".equals(period) && hour == 12 && !numericNoon;
            if (("下午".equals(period) || "晚上".equals(period) || "晚间".equals(period)) && hour < 12) hour += 12;
            if ("凌晨".equals(period) && hour == 12 && !numericNoon) continue;
            if (period == null && matcher.group(3) == null && hour > 0 && hour < 12) continue;
            try {
                result.add(new Clock(LocalTime.of(hour, minute, second, nano), matcher.group(), review,
                        matcher.start(), matcher.end(), review || hour != originalHour || matcher.group(3) == null ? period : null,
                        matcher.group(5) != null ? "FRACTION" : matcher.group(4) != null ? "SECOND" : "MINUTE"));
            } catch (java.time.DateTimeException ignored) { /* Unsupported source clock is never made exact. */ }
        }
        return result;
    }

    private boolean explicitOffset(String text, int start, String token) {
        if (!token.startsWith("-")) return true;
        // A bare 17:00-18:00 is a clock interval, not a negative zone offset.
        String prefix = text.substring(0, start);
        return text.startsWith("T") || prefix.contains("UTC") || prefix.contains("GMT")
                || prefix.matches("(?s).*[0-9]{1,2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]+)?");
    }

    private int number(String text) {
        if (text.matches("[0-9]+")) return Integer.parseInt(text);
        String digits = "零一二三四五六七八九";
        text = text.replace('〇', '零').replace('两', '二');
        int ten = text.indexOf('十');
        if (ten >= 0) return (ten == 0 ? 1 : digits.indexOf(text.charAt(0))) * 10
                + (ten == text.length() - 1 ? 0 : digits.indexOf(text.charAt(ten + 1)));
        return text.length() == 1 ? digits.indexOf(text.charAt(0)) : -1;
    }

    private void fail(int index, String reason) { throw new IllegalArgumentException("timeline[" + index + "]: " + reason); }

    private record DateMention(Integer year, int month, int day, int start, int end, String text) { }
    private record Clock(LocalTime time, String text, boolean review, int start, int end, String period, String precision) {
        boolean supports(LocalTime expected) {
            return time.equals(expected) || review && expected.getHour() == 0
                    && time.withHour(0).equals(expected);
        }
    }
    record TimeEvidence(int nodeIndex, String field, String generatedValue, String dateSource, String yearSource,
                        String clockSource, String timezoneSource, String precision, boolean requiresReview) { }
}
