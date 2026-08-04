package com.paicli.mcp.mention;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @-mention 解析器。
 *
 * 从用户输入中识别 @server:protocol://path 格式的 resource 引用。
 * 支持引号内文本跳过（避免字符串字面量中的误匹配）。
 */
public final class AtMentionParser {
    static final Pattern RESOURCE_PATTERN = Pattern.compile("@([a-zA-Z][\\w-]*):([a-z]+)://([^\\s@]+)");

    private AtMentionParser() {
    }

    public static List<MentionToken> parse(String input) {
        if (input == null || input.isBlank()) {
            return List.of();
        }
        List<MentionToken> tokens = new ArrayList<>();
        Matcher matcher = RESOURCE_PATTERN.matcher(input);
        while (matcher.find()) {
            if (isInsideQuotes(input, matcher.start())) {
                continue;
            }
            String serverName = matcher.group(1);
            String uri = matcher.group(2) + "://" + matcher.group(3);
            tokens.add(new MentionToken(serverName, uri, matcher.start(), matcher.end(), matcher.group()));
        }
        return tokens;
    }

    private static boolean isInsideQuotes(String input, int offset) {
        boolean inSingle = false;
        boolean inDouble = false;
        boolean escaped = false;
        for (int i = 0; i < offset; i++) {
            char c = input.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            }
        }
        return inSingle || inDouble;
    }

    public record MentionToken(String serverName, String uri, int start, int end, String raw) {
    }
}
