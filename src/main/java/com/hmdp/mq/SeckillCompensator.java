package com.hmdp.mq;

import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.core.io.ClassPathResource;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * redis记录回滚
 */
@Slf4j
@Component
public class SeckillCompensator {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    //redis数据回滚
    private static final DefaultRedisScript<Long> ROLLBACK_SCRIPT;


    static {
        ROLLBACK_SCRIPT=new DefaultRedisScript<>();
        ROLLBACK_SCRIPT.setLocation(new ClassPathResource("rollback.lua"));
        ROLLBACK_SCRIPT.setResultType(Long.class);
    }


    public void rollback(Long voucherId, Long userId) {
        Long r = stringRedisTemplate.execute(
                ROLLBACK_SCRIPT,
                Arrays.asList(
                        RedisConstants.seckillStockKey(voucherId),
                        RedisConstants.seckillOrderKey(voucherId)
                ),
                userId.toString()
        );
        if (r!=null && r==1L){
            log.info("补偿回滚成功：voucherId={}, userId={}", voucherId, userId);
        }else{
            log.info("补偿回滚无需执行：voucherId={}, userId={},r={}", voucherId, userId,r);
        }
    }
}
