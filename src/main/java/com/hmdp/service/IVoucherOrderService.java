package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {
    /** 秒杀下单：Lua预检 + 发MQ，立即返回订单号 */
    Result seckillVoucher(Long voucherId);
    /** 消费者异步调用：创建订单并落库 */
    void createVoucherOrder(VoucherOrder voucherId);
}
