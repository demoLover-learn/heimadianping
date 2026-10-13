package com.hmdp.mq;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.MQConstants;
import com.rabbitmq.client.Channel;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;


/**
 * 死信补偿消费者：订单落库失败进 DLQ 后，在这里做兜底闭环
 * 先查订单在不在 DB：
 *   在   → 只是重复投递/MQ侧误伤，直接确认
 *   不在 → Redis 那次预扣成了幽灵占位，幂等回滚，然后确认
 */
@Component
@Slf4j
public class SeckillDlqConsumer {
    @Autowired
    private IVoucherOrderService voucherOrderService;
    @Autowired
    private SeckillCompensator seckillCompensator;

    @RabbitListener(queues = MQConstants.SECKILL_DLQ)
    public void dlxListen(VoucherOrder voucherOrder,
                          Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG)long deliveryTag) throws IOException {
     try{ if(voucherOrderService.getById(voucherOrder.getId())!=null){
          //订单已经落库->幂等成功
          log.info("死信补偿：订单已经存在无需处理orderId:{}",voucherOrder.getId());
      }else{
          //订单没有落库，做兜底回滚
          log.debug("死信补偿：订单落库失败，执行回滚 orderId={}, voucherId={}, userId={}",
                  voucherOrder.getId(), voucherOrder.getVoucherId(), voucherOrder.getUserId());
          seckillCompensator.rollback(voucherOrder.getVoucherId(), voucherOrder.getUserId());
      }
      channel.basicAck(deliveryTag, false);
     }catch (Exception e){
         log.info("死信补偿：订单落库失败，执行回滚 orderId={}, voucherId={}, userId={}",
                 voucherOrder.getId(), voucherOrder.getVoucherId(), voucherOrder.getUserId());
         log.error("死信补偿失败，放弃本条消息 orderId={}", voucherOrder.getId(), e);
         channel.basicNack(deliveryTag, false, false);
     }
    }
}
