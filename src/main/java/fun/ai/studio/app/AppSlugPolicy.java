package fun.ai.studio.app;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * appSlug 规则：由前端人工填写，后端负责规范化、校验与保留词拦截。
 */
public final class AppSlugPolicy {

    private static final Pattern APP_SLUG_PATTERN = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 40;

    private static final Set<String> RESERVED = Set.of(
            "api",
            "doc",
            "docs",
            "admin",
            "login",
            "register",
            "runtime",
            "preview",
            "swagger-ui",
            "assets",
            "static"
    );

    private AppSlugPolicy() {
    }

    public static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    public static boolean isValidFormat(String slug) {
        if (slug == null) return false;
        if (slug.length() < MIN_LENGTH || slug.length() > MAX_LENGTH) return false;
        return APP_SLUG_PATTERN.matcher(slug).matches();
    }

    public static boolean isReserved(String slug) {
        return slug != null && RESERVED.contains(slug);
    }

    public static String validationMessage(String slug) {
        if (slug == null || slug.isBlank()) {
            return "appSlug 不能为空";
        }
        if (slug.length() < MIN_LENGTH || slug.length() > MAX_LENGTH) {
            return "appSlug 长度必须在 " + MIN_LENGTH + " 到 " + MAX_LENGTH + " 个字符之间";
        }
        if (!APP_SLUG_PATTERN.matcher(slug).matches()) {
            return "appSlug 仅允许小写字母、数字和短横线，且不能以短横线开头或结尾";
        }
        if (RESERVED.contains(slug)) {
            return "appSlug 为系统保留词，请更换";
        }
        return null;
    }
}
