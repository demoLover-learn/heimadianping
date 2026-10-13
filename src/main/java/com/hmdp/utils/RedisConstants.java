package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 30L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;
    //秒杀库存key
    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    //秒杀已下单用户的key
    public static final String SECKILL_ORDER_KEY = "seckill:order:";
    //秒杀开始时间
    public static final String SECKILL_BEGIN_TIME_KEY="seckill:begin_time:";
    //秒杀结束时间
    public static final String SECKILL_END_TIME_KEY="seckill:end_time:";
    //参与对账的秒杀活动集合（对账任务的入口；单个key，不需要hash tag）
    public static final String SECKILL_VOUCHERS_KEY = "seckill:vouchers";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";


    /**
     * 秒杀库存key
     */
    public static String seckillStockKey(Long voucherId){
        return SECKILL_STOCK_KEY + "{"+voucherId+"}";
    }

    /**
     * 秒杀已下单的用户集合
     */
    public static String seckillOrderKey(Long voucherId){
        return SECKILL_ORDER_KEY + "{"+voucherId+"}";
    }
    //秒杀开始时间
    public static String seckillBeginTime(Long voucherId){
        return SECKILL_BEGIN_TIME_KEY + "{"+voucherId+"}";
    }//
    //秒杀结束时间
    public static String seckillEndTime(Long VoucherId){
        return SECKILL_END_TIME_KEY + "{"+VoucherId+"}";
    }
}
