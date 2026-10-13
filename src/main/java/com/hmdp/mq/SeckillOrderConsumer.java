package com.hmdp.mq;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.MQConstants;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import java.io.IOException;

@Slf4j
@Component
public class SeckillOrderConsumer {

    @Autowired
    private IVoucherOrderService voucherOrderService;

    @RabbitListener(queues = MQConstants.SECKILL_QUEUE)
    public void handSeckillOrder( VoucherOrder voucherOrder,
                                 Channel channel,
                                 @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            voucherOrderService.createVoucherOrder(voucherOrder);
            // 处理成功：手动确认
            channel.basicAck(deliveryTag,false);
        } catch (DuplicateKeyException e) {
            // 唯一索引拦下的并发重复：幂等成功，直接确认（事务已回滚，多扣的库存已还回）
            log.warn("重复订单已被唯一索引拦截，直接确认消息：userId={}, voucherId={}",
                    voucherOrder.getUserId(), voucherOrder.getVoucherId());
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            // 其他失败：拒绝且不重回原队列 → 进入死信队列
            log.error("处理秒杀订单失败，消息进入死信队列：{}", voucherOrder, e);
            //mutiple:是否批量拒绝，requeue:是否重新入队
            channel.basicNack(deliveryTag, false, false);
        }


    }
}
