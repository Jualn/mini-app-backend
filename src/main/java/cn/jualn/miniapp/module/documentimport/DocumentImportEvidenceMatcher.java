package cn.jualn.miniapp.module.documentimport;

import java.util.ArrayList;
import java.util.List;

/** Layout-only matching with offsets back to the unchanged source. No fuzzy or semantic matching. */
final class DocumentImportEvidenceMatcher {
    private final String source;
    private Normalized normalizedSource;

    DocumentImportEvidenceMatcher(String source) {
        this.source = source;
    }

    Match match(String excerpt) {
        int exact = source.indexOf(excerpt);
        if (exact >= 0) return new Match("EXACT", exact, exact + excerpt.length(), excerpt);
        if (normalizedSource == null) normalizedSource = normalize(source);
        String needle = normalize(excerpt).text();
        if (needle.isBlank()) return null;
        int at = normalizedSource.text().indexOf(needle);
        if (at < 0) return null;
        int start = normalizedSource.starts().get(at);
        int end = normalizedSource.ends().get(at + needle.length() - 1);
        String original = source.substring(start, end);
        if (original.length() > 4000) return null;
        return new Match("LAYOUT_WHITESPACE", start, end, original);
    }

    private Normalized normalize(String input) {
        StringBuilder text = new StringBuilder();
        List<Integer> starts = new ArrayList<>();
        List<Integer> ends = new ArrayList<>();
        for (int i = 0; i < input.length();) {
            int start = i;
            char current = input.charAt(i++);
            if (layoutWhitespace(current)) {
                int lines = current == '\n' ? 1 : 0;
                while (i < input.length() && layoutWhitespace(input.charAt(i))) {
                    if (input.charAt(i++) == '\n') lines++;
                }
                // Blank paragraphs are a boundary. Form feeds and all other characters are untouched.
                if (lines > 1) {
                    for (int j = start; j < i; j++) append(text, starts, ends, input.charAt(j), j, j + 1);
                    continue;
                }
                // Han text and Han/ASCII-digit unit boundaries only. Never join digits or Latin words.
                if (start > 0 && i < input.length() && removableGap(input.charAt(start - 1), input.charAt(i))) continue;
                current = ' ';
            }
            append(text, starts, ends, current, start, i);
        }
        return new Normalized(text.toString(), starts, ends);
    }

    private void append(StringBuilder text, List<Integer> starts, List<Integer> ends, char character, int start, int end) {
        text.append(character);
        starts.add(start);
        ends.add(end);
    }

    private boolean layoutWhitespace(char character) {
        return character == ' ' || character == '\t' || character == '\r' || character == '\n';
    }

    private boolean han(char character) {
        return Character.UnicodeScript.of(character) == Character.UnicodeScript.HAN;
    }

    private boolean removableGap(char left, char right) {
        return (han(left) && (han(right) || asciiDigit(right))) || (asciiDigit(left) && han(right));
    }

    private boolean asciiDigit(char character) { return character >= '0' && character <= '9'; }

    record Match(String mode, int start, int end, String original) { }
    private record Normalized(String text, List<Integer> starts, List<Integer> ends) { }
}
