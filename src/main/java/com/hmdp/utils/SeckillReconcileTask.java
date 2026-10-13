package com.hmdp.utils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mq.SeckillCompensator;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;

@Component
@Slf4j
public class SeckillReconcileTask {
    @Autowired
    private  StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RabbitAdmin rabbitAdmin;
    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private IVoucherOrderService voucherOrderService;
    @Autowired
    private SeckillCompensator seckillCompensator;


    /** 每 5 分钟兜底扫一次（生产一般放低峰期定时执行） */
    @Scheduled(fixedDelay = 5 * 60 * 1000)
    public void reconcile() {
      try {  //集合不存在，说明当前没有任何活动被注册进对账系统，对账任务没有任务可做，所以直接 return 结束。
          Set<String> voucherIds = stringRedisTemplate.opsForSet()
                  .members(RedisConstants.SECKILL_VOUCHERS_KEY);
          if (voucherIds == null || voucherIds.isEmpty()) {
              //有的话直接返回
              return;
          }
          //如果有消息在途中就停止
          QueueInformation queueInfo = rabbitAdmin.getQueueInfo(MQConstants.SECKILL_QUEUE);
          if (queueInfo == null || queueInfo.getMessageCount() > 0) {
              log.info("对账跳过：订单队列还有在途消息 count={}",
                      queueInfo == null ? "-" : queueInfo.getMessageCount());
              return;
          }
          for (String voucherIdStr : voucherIds) {
              Long voucherId = Long.valueOf(voucherIdStr);
              //活动已经结束才允许修复
              String endStr = stringRedisTemplate.opsForValue().get(RedisConstants.seckillEndTime(voucherId));
              if (endStr == null || System.currentTimeMillis() <= Long.parseLong(endStr)) {
                  continue;
              }
              int repaired = reconcileVoucher(voucherId);
              if (repaired > 0) {
                  log.error("对账修复完成：voucherId={}, 修复幽灵占位 {} 处（生产此处应接告警）",
                          voucherId, repaired);
              }
          }

    } catch (Exception e) {
        log.error("对账任务执行异常", e);
    }

}

    /** 对单个活动对账；返回修复条数 */
    private int reconcileVoucher(Long voucherId) throws IOException {
        int repaired = 0;
        // 大集合用 SSCAN 增量遍历（生产别用 SMEMBERS/KEYS）
        try (Cursor<String> cursor = stringRedisTemplate.opsForSet().scan(
                RedisConstants.seckillOrderKey(voucherId),
                ScanOptions.scanOptions().count(200).build())) {
            while (cursor.hasNext()) {
                Long userId = Long.valueOf(cursor.next());
                int count = voucherOrderService.count(new LambdaQueryWrapper<VoucherOrder>()
                        .eq(VoucherOrder::getUserId, userId)
                        .eq(VoucherOrder::getVoucherId, voucherId));
                if (count == 0) {
                    log.error("发现幽灵占位，执行回滚：voucherId={}, userId={}", voucherId, userId);
                    seckillCompensator.rollback(voucherId, userId);
                    repaired++;
                }
            }
        }
        // 修复后自校验：两边库存应当相等
        String redisStock = stringRedisTemplate.opsForValue()
                .get(RedisConstants.seckillStockKey(voucherId));
        SeckillVoucher dbVoucher = seckillVoucherService.getById(voucherId);
        if (redisStock != null && dbVoucher != null
                && !redisStock.equals(dbVoucher.getStock().toString())) {
            log.error("对账后库存仍不一致：voucherId={}, redis={}, db={}",
                    voucherId, redisStock, dbVoucher.getStock());
        }
        return repaired;

}
}
