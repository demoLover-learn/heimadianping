package com.hmdp;

import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Shop;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.MQConstants;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SeckillReconcileTask;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.cache.CacheProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {
    @Autowired
    private ShopServiceImpl shopService;
    @Autowired
    private RedisIdWorker redisIdWorker;

    @Test
    void contextLoads() {
        long l = redisIdWorker.nextId("order");
        System.out.println(l);
    }


    @Test
    void testRedis() throws InterruptedException {
        shopService.shop2Redis(1L,10L);
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Test
    void testSendDeclaration() {
        rabbitTemplate.convertAndSend(MQConstants.SECKILL_EXCHANGE,
                MQConstants.SECKILL_ROUTING_KEY, "declare-check");
    }


    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    public void testPreheatSeckill() {

        Long voucherId = 12L;

        // 1. 从DB读库存，写入Redis（预热）
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        stringRedisTemplate.opsForValue().set(
                RedisConstants.seckillStockKey(voucherId),
                voucher.getStock().toString());
        //秒杀开始时间也要写入redis
        stringRedisTemplate.opsForValue().set(
                RedisConstants.seckillBeginTime(voucherId),
                String.valueOf(voucher.getBeginTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
        );
        //结束时间
        stringRedisTemplate.opsForValue().set(
                RedisConstants.seckillEndTime(voucherId),
                String.valueOf(voucher.getEndTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
        );

        // 2. 清掉「已下单用户」集合，方便反复测试
        stringRedisTemplate.delete(RedisConstants.seckillOrderKey(voucherId));



        // 3. 注册到「参与对账的活动」集合（对账任务入口）
        stringRedisTemplate.opsForSet().add(RedisConstants.SECKILL_VOUCHERS_KEY, voucherId.toString());


        System.out.println("预热完成：" + RedisConstants.seckillStockKey(voucherId)
                + " = " + voucher.getStock());
    }

    @Test
    public void testSendWrongRoutingKey() {
        rabbitTemplate.convertAndSend("seckill.direct", "不存在的key", "test");
        // 观察日志：应该打印「消息路由失败：...」
    }

    @Autowired
    private SeckillReconcileTask seckillReconcileTask;

    @Test
    public void testReconcileManually() {
        seckillReconcileTask.reconcile();
    }

    @Test
    void loadShopData(){
        //查询店铺信息
        List<Shop> list = shopService.list();
        //把店铺按照typeId分组，typeId一致的放到一个集合
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //分批写入redis
        for(Map.Entry<Long, List<Shop>> entry : map.entrySet()){
            //获取类型id
            Long typeId = entry.getKey();
            String key="shop:geo:"+typeId;
            //获取同类型店铺的集合
            List<Shop> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations=new ArrayList<>(value.size());
            //写入redis geoadd key 经度 维度 member
            for (Shop shop : value) {
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(), shop.getY())
                ));
            }
            stringRedisTemplate.opsForGeo().add(key, locations);
        }



    }




}
