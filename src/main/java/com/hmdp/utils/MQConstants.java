package com.hmdp.utils;

/**
 * Rabbitmq常量：交换机、队列、路由key
 */
public class MQConstants {
    //秒杀业务交换机
    public static final String SECKILL_EXCHANGE="seckill.direct";
    //秒杀订单队列
    public static final String SECKILL_QUEUE="seckill.order.queue";
    //秒杀订单路由Key
    public static final String SECKILL_ROUTING_KEY="seckill.order";

    //死信交换机
    public static final String SECKILL_DLX_EXCHANGE="seckill.dlx.direct";
    //死信队列
    public static final String SECKILL_DLQ="seckill.order.dlq";
    //死信路由key
    public static final String SECKILL_DLQ_ROUTING_KEY="seckill.order.dlq";
}
