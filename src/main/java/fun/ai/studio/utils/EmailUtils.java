package fun.ai.studio.utils;

import java.util.regex.Pattern;

/**
 * 邮箱工具类
 */
public class EmailUtils {

    // 简单的邮箱格式校验正则
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");

    /**
     * 邮箱脱敏显示
     * 例如: fun@example.com -> fu***@example.com
     *
     * @param email 原始邮箱
     * @return 脱敏后的邮箱
     */
    public static String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return email;
        }
        String[] parts = email.split("@");
        String name = parts[0];
        String domain = parts[1];

        if (name.length() <= 2) {
            return name.charAt(0) + "***@" + domain;
        }

        return name.substring(0, 2) + "***@" + domain;
    }

    /**
     * 检测邮箱格式是否有效
     * 排除 "string" 这类无效邮箱
     *
     * @param email 邮箱地址
     * @return true=有效，false=无效
     */
    public static boolean isValidEmail(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        // 排除明显无效的邮箱
        if ("string".equalsIgnoreCase(email) || email.length() < 5) {
            return false;
        }
        return EMAIL_PATTERN.matcher(email).matches();
    }
}
