package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.mq.SeckillCompensator;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.MQConstants;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Arrays;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private SeckillCompensator seckillCompensator;
    //秒杀预检脚本，类加载时读取一次
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static{
        SECKILL_SCRIPT=new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    /**
     * 秒杀下单:redis+lua原子预减->发MQ异步落库->立刻返回订单号
     * @param voucherId
     * @return
     */

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        //执行lua:判断库存+判断一人一单+预扣
        Long r = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Arrays.asList(
                        RedisConstants.seckillStockKey(voucherId),
                        RedisConstants.seckillOrderKey(voucherId),
                        RedisConstants.seckillBeginTime(voucherId),
                        RedisConstants.seckillEndTime(voucherId)
                ),
                userId.toString());
        if (r == null){
            return Result.fail("系统繁忙");
        }
        //2.检查预期结果
        int result = r.intValue();
        switch (result) {
            case 1: return Result.fail("库存不足");
            case 2: return Result.fail("不能重复下单");
            case 3: return Result.fail("秒杀尚未开始");
            case 4: return Result.fail("秒杀已经结束");
            case 0: break;                    // 放行：生成 orderId → 发 MQ → return Result.ok(orderId)
            default: return Result.fail("系统繁忙");
        }
        //预检通过：生成订单号，构造订单
        long orderId = redisIdWorker.nextId("order");
        VoucherOrder voucherOrder = new VoucherOrder()
                .setId(orderId)
                .setUserId(userId)
                .setVoucherId(voucherId);
        //发消息，交给消费者异步落库
       try{ rabbitTemplate.convertAndSend(
                MQConstants.SECKILL_EXCHANGE,MQConstants.SECKILL_ROUTING_KEY,
                voucherOrder,
               //专门用来在异步回调时携带上下文信息
               new CorrelationData(voucherId+":"+userId)
        );
       }catch (Exception e){
           //回滚redis数据
           log.error("发送秒杀消息失败，回滚预扣：orderId={}", orderId, e);
           seckillCompensator.rollback(voucherId,userId);
          return Result.fail("下单失败请重试");
    }
        //立刻返回订单号
        return Result.ok(orderId);
    }

    /**
     * 消费端调用：订单落库，事务保证「查重 + 扣库存 + 写订单」原子
     * @param voucherOrder
     * @return
     */
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder){
        //一人一单
        Long userId =voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        //查询订单
        Integer count = query().eq("user_id", userId)
                .eq("voucher_id", voucherId).count();
        //判断是否存在
        if (count>0){
            log.info("用户已下过单，跳过重复订单：userId={}, voucherId={}", userId, voucherId);
            return;
        }
        //扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id", voucherId)
                .gt("stock",0)
                .update();
        if (!success) {
            // 抛异常 → 事务回滚 → 消费者 nack → 进死信队列人工处理
            throw new RuntimeException("数据库库存不足，创建订单失败: " +
                    voucherOrder.getId());
        }
        //  写订单。并发重复由唯一索引 uk_user_voucher 拦截
        // （抛 DuplicateKeyException 触发回滚，
        // 把第2步多扣的库存还回去）
       save(voucherOrder);
    }
}
