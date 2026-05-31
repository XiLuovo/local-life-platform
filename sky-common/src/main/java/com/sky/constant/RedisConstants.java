package com.sky.constant;

public final class RedisConstants {

    private RedisConstants() {
    }

    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final long LOGIN_CODE_TTL = 2L;
    public static final String CACHE_STORE_KEY = "cache:store:";
    public static final long CACHE_STORE_TTL = 30L;
    public static final long CACHE_NULL_TTL = 2L;
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String FOLLOW_KEY = "follows:";
    public static final String USER_SIGN_KEY = "sign:";
    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String STREAM_ORDERS_KEY = "stream.orders";
}
