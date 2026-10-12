package kr.academy.attendance;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared send-time rules with no Android dependency, so they can be tested on the JVM. */
public final class SmsRules {
    private SmsRules() {}
    private static final String SPACE = "\\s\\p{Zs}\\u2028\\u2029\\uFEFF";
    public static String phone(String raw) {
        if (raw == null || !raw.matches("[+0-9" + SPACE + "().-]*")) return "";
        String value = raw.replaceAll("[" + SPACE + "().-]", "");
        if (value.startsWith("+82")) value = "0" + value.substring(3).replaceFirst("^0", "");
        return value.matches("\\+?[0-9]{8,15}") ? value : "";
    }
    public static String render(String template, Map<String, String> values) {
        Matcher m = Pattern.compile("\\{([^{}]+)\\}").matcher(template);
        StringBuffer out = new StringBuffer();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(values.containsKey(m.group(1)) ? values.get(m.group(1)) : m.group()));
        m.appendTail(out); return out.toString();
    }
    public static String key(String student, String date, String status, String phone) {
        return student + "|" + date + "|" + status + "|" + phone(phone);
    }
}
