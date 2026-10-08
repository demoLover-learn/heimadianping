package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;



    @Override
    public Result getAll() {
        String key = "cache:shopType:" + UUID.randomUUID();
        //查缓存是否存在
        String cache = stringRedisTemplate.opsForValue().get(key);
        //存在的话直接返回
        if(StrUtil.isNotBlank(cache)){
            return Result.ok(JSONUtil.toList(cache, ShopType.class));
        }
        //不存在的话，查数据库库
        List<ShopType> shopTypeList =list();
        //存入缓存中
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shopTypeList),2L, TimeUnit.HOURS);
        //返回结果

//        List<ShopType> typeList = typeService
//                .query().orderByAsc("sort").list();
        return Result.ok(shopTypeList);
    }
}
