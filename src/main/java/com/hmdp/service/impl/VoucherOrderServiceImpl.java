package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;



    /**
     * 秒杀下单
     * @param voucherId
     * @return
     */

    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.查询优惠卷
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //判断秒杀是否开始
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            //尚未开始
            return Result.fail("秒杀尚未开始");
        }
        //判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束");
        }
        //判断库存是否充足
        Integer stock = voucher.getStock();
        if (stock < 1) {
            return Result.fail("库存不足！！");
        }
        Long userId = UserHolder.getUser().getId();
        //返回订单id
        synchronized (userId.toString().intern()) {
            //获取代理对象(事务)
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
    }
    @Transactional
    public Result createVoucherOrder(Long voucherId){
        //一人一单
        Long userId = UserHolder.getUser().getId();
        //查询订单
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        //判断是否存在
        if (count>0){
            return Result.fail("该用户已经买过一次");
        }
        //扣减库存
        boolean success = seckillVoucherService.update().setSql("stock=stock-1")
                .eq("voucher_id", voucherId).gt("stock",0)
                .update();
        if (!success) {
            return Result.fail("库存不足！！");
        }
        //创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //代金券id
        voucherOrder.setVoucherId(voucherId);
        //用户id

        voucherOrder.setUserId(userId);
        //订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        save(voucherOrder);
        return Result.ok(orderId);
    }
}
