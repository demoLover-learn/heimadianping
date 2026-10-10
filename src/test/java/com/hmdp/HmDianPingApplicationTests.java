package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

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


}
