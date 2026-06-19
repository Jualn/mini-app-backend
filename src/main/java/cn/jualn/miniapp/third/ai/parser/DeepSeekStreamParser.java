package cn.jualn.miniapp.third.ai.parser;

import org.springframework.stereotype.Component;

@Component
public class DeepSeekStreamParser {

    /**
     * 从buf中切出一行文本，行以换行符分隔，但换行符在字符串内部不切行。切出的行会从buf中删除。
     * 字符串以双引号括起来，双引号内部的内容不切行，双引号内部的转义字符（比如\"）也不切行。比如下面这个文本：
     *   line1\n line2 "line3\n line4" line5
     * 会被切成三行：
     *   line1
     *   line2 "line3\n line4"
     *   line5
     * @param buf 文本缓冲区，可能包含多行文本
     * @return 切出的行文本，如果没有完整行则返回null
     */
    public String pollLine(StringBuilder buf) {
        boolean inStr = false;
        boolean escaped = false;
        for (int i = 0; i < buf.length(); i++) {
            char c = buf.charAt(i);
            if (escaped)       { escaped = false; continue; }
            if (c == '\\')     { escaped = true;  continue; }
            if (c == '"')      { inStr = !inStr;  continue; }
            if (c == '\n' && !inStr) {          // 只在字符串外部才切行
                String line = buf.substring(0, i).trim();
                buf.delete(0, i + 1);
                return line.isEmpty() ? null : line;
            }
        }
        return null;  // 当前没有完整行
    }
}
