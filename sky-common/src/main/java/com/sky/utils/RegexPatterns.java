package com.sky.utils;

public final class RegexPatterns {

    private RegexPatterns() {
    }

    public static final String PHONE_REGEX = "^1([3-9])\\d{9}$";
    public static final String VERIFY_CODE_REGEX = "^\\d{6}$";
}
