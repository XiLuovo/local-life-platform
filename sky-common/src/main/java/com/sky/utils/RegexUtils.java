package com.sky.utils;

public final class RegexUtils {

    private RegexUtils() {
    }

    public static boolean isPhoneInvalid(String phone) {
        return isBlank(phone) || !phone.matches(RegexPatterns.PHONE_REGEX);
    }

    public static boolean isCodeInvalid(String code) {
        return isBlank(code) || !code.matches(RegexPatterns.VERIFY_CODE_REGEX);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
