package com.paicli.memory;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从用户输入中自动识别需要写入长期记忆的意图。
 *
 * 当用户说"记一下/记住/以后记得"并同时提及浏览器登录复用时，
 * 自动提炼出"访问某网站优先复用已登录 Chrome"的记忆事实，
 * 写入 MemoryManager，跨会话生效。
 */
public final class ExplicitMemoryHints {
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s，。！？、)）]+");

    private ExplicitMemoryHints() {
    }

    /**
     * 判断是否需要自动保存一条"浏览器登录复用"长期记忆。
     *
     * @return 要保存的精炼事实，或 null（不需要保存）
     */
    public static String browserLoginFact(String userInput, List<String> recentTexts) {
        String current = userInput == null ? "" : userInput;
        if (!hasExplicitRememberIntent(current) || !mentionsBrowserLoginReuse(current)) {
            return null;
        }
        String joined = String.join("\n", recentTexts == null ? List.of() : recentTexts) + "\n" + current;
        String host = lastUrlHost(joined);
        if (host == null && (containsIgnoreCase(joined, "yuque") || joined.contains("语雀"))) {
            host = "yuque.com";
        }
        if (host == null) {
            return "用户明确允许在需要登录态的网站访问中复用已登录 Chrome。";
        }
        String label = host.contains("yuque.com") ? "（语雀）" : "";
        String separator = label.isEmpty() ? " 时" : "时";
        return "访问 " + host + label + separator + "优先复用用户已登录的 Chrome 登录态。";
    }

    private static boolean hasExplicitRememberIntent(String text) {
        return text.contains("记一下")
                || text.contains("记住")
                || text.contains("记下来")
                || text.contains("以后记得")
                || text.contains("下次记得")
                || text.contains("保存这个偏好")
                || text.contains("保存到长期记忆");
    }

    private static boolean mentionsBrowserLoginReuse(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        boolean browser = lower.contains("chrome") || text.contains("浏览器");
        boolean login = text.contains("登录态") || text.contains("已登录") || text.contains("登录好的");
        boolean reuse = text.contains("复用") || text.contains("直接用") || text.contains("连接");
        return browser && (login || reuse);
    }

    private static boolean containsIgnoreCase(String text, String needle) {
        return text.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private static String lastUrlHost(String text) {
        Matcher matcher = URL_PATTERN.matcher(text == null ? "" : text);
        String host = null;
        while (matcher.find()) {
            try {
                String candidate = URI.create(matcher.group()).getHost();
                if (candidate != null && !candidate.isBlank()) {
                    host = normalizeHost(candidate);
                }
            } catch (IllegalArgumentException ignored) {
                // 忽略坏 URL，继续找前文里其他 URL。
            }
        }
        return host;
    }

    private static String normalizeHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.startsWith("www.") ? normalized.substring(4) : normalized;
    }
}
