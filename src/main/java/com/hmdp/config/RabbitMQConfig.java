package com.hmdp.config;


import com.hmdp.mq.SeckillCompensator;
import com.hmdp.utils.MQConstants;


import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;


// RabbitMQ 配置：交换机/队列/绑定 + 死信 + JSON消息转换器 + 发送端回调
@Configuration
@EnableRabbit
@Slf4j
public class RabbitMQConfig {
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private SeckillCompensator seckillCompensator;


    //消息体用json序列化
    @Bean
    public MessageConverter jsonMessageConvert(){
       return new Jackson2JsonMessageConverter();
    }



    /**------秒杀业务--------**/

    //业务交换机：direct类型，按routingKey精确路由
    @Bean
    public DirectExchange seckillExchange(){
        return  new DirectExchange(MQConstants.SECKILL_EXCHANGE,true,false);
    }
    //订单队列：持久化+绑定死信交换机
    @Bean
    public Queue seckillOrderQueue(){
        return QueueBuilder.durable(MQConstants.SECKILL_QUEUE)
                .deadLetterExchange(MQConstants.SECKILL_DLX_EXCHANGE)
                .deadLetterRoutingKey(MQConstants.SECKILL_DLQ_ROUTING_KEY)
                .build();

    }
    //把队列绑定到交换机上
    @Bean
    public Binding seckillOrderBinding(){
        return BindingBuilder.bind(seckillOrderQueue())
                .to(seckillExchange())
                .with(MQConstants.SECKILL_ROUTING_KEY);
    }
    //死信交换机的相关配置
    @Bean
    public DirectExchange seckillDlxExchange(){
        //false含义：当所有绑定到这个交换机的队列都解绑（或者被删除）后，这个交换机是否要自动删除自己。
        return new DirectExchange(MQConstants.SECKILL_DLX_EXCHANGE,true,false);
    }
    //死信队列
    @Bean
    public Queue seckillDlq(){
        return  QueueBuilder.durable(MQConstants.SECKILL_DLQ).build();
    }
    //死信队列的绑定
    @Bean
    public Binding seckillDlqBinding(){
        return  BindingBuilder.bind(seckillDlq())
                .to(seckillDlxExchange())
                .with(MQConstants.SECKILL_DLQ_ROUTING_KEY);
    }

    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        admin.setAutoStartup(true);
        return admin;
    }


    /* ---------------- 发送端可靠性回调 ---------------- */

    @PostConstruct
    public void initCallbacks() {
        // confirm：消息有没有到达交换机
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (ack) {
                log.info("消息已经确认到达交换机");
                return;
            }
            log.info("消息发送失败，未到达交换机！cause:{}", cause);
            if (correlationData != null && correlationData.getId()!=null) {
                String[] parts = correlationData.getId().split(":");
                if (parts.length == 2) {
                    //voucherId,userId
                    seckillCompensator.rollback(Long.valueOf(parts[0]), Long.valueOf(parts[1]));
                }
            }
        });
        rabbitTemplate.setReturnCallback((message, replyCode, replyText, exchange, routingKey) -> {
            log.error("消息路由失败：exchange={}, routingKey={}, replyText={}",
                    exchange, routingKey, replyText);
        });

    }





}
