package com.truckgo.user;

import java.util.Locale;
import java.util.regex.Pattern;

/** Canonical forms used for storage, uniqueness and login lookup: lower-case e-mail, E.164 (+84) Vietnamese mobile number. */
public final class ContactNormalizer {
    private static final Pattern EMAIL = Pattern.compile("^[a-z0-9._%+-]+@[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z]{2,}$");
    private static final Pattern VN_MOBILE = Pattern.compile("^\\+84[35789][0-9]{8}$");

    private ContactNormalizer() { }

    /** Returns null for blank input; throws IllegalArgumentException for an invalid address. */
    public static String email(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.length() > 254 || !EMAIL.matcher(value).matches()) throw new IllegalArgumentException("Email không hợp lệ");
        return value;
    }

    /** Accepts 0912345678, 84912345678, +84 912 345 678, 0912.345.678 … and returns +84912345678. */
    public static String phone(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String digits = raw.trim().replaceAll("[\\s.\\-()]", "");
        if (digits.startsWith("+84")) digits = digits.substring(3);
        else if (digits.startsWith("84") && digits.length() == 11) digits = digits.substring(2);
        else if (digits.startsWith("0")) digits = digits.substring(1);
        String value = "+84" + digits;
        if (!VN_MOBILE.matcher(value).matches()) throw new IllegalArgumentException("Số điện thoại không hợp lệ");
        return value;
    }

    public static boolean looksLikeEmail(String identifier) { return identifier != null && identifier.contains("@"); }
}
