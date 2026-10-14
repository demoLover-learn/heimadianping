package com.hmdp.service.impl;

import cn.hutool.core.io.resource.StringResource;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 根据id查询商铺信息
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        //逻辑过期解决缓存击穿
        Shop shop = queryWithLogicalExpire(id);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    /**
     * 更新商铺信息
     * @param shop
     * @return
     */
    @Transactional
    @Override
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺不能为空");
        }
        //更新数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+ id);
        return null;
    }
    /**
     * 根据商铺类型分页查询商铺信息
     * @param typeId 商铺类型
     * @param current 页码
     * @return 商铺列表
     */
    @Override
    public Result queryShopByType(Integer typeId, Integer current,
                                  Double x, Double y) {
        //判断是否需要根据坐标查询
        if (x==null || y==null) {
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            //返回数据
            return Result.ok(page.getRecords());
        }
        //计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end=current * SystemConstants.DEFAULT_PAGE_SIZE;
        //查询redis，按照举例排序、分页。解锁：shopId、distance
        String key=SHOP_GEO_KEY+typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()//geosearch key bylonlat x y BYRADIUS 10 WITHDISTANCE
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),//以用户坐标为中心点
                        new Distance(5000),//	搜索半径 5000 米
                        RedisGeoCommands.GeoSearchCommandArgs
                                .newGeoSearchArgs()
                                .includeDistance()//结果中带上距离
                                .limit(end)//最多返回 end 条（模拟分页，先多取再截)
                );
        if (results==null){
            return Result.ok();
        }
        //解析出id
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size()<=from){
            //没有下一页
            return Result.ok();
        }
        //截取from~end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String,Distance> distanceMap=new HashMap<>(list.size());
        list.stream().skip(from).forEach(result->{
            //获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.parseLong(shopIdStr));
            //获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr,distance);
        });
        //根据id查shop
       String idStr=ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        List<Shop> shops = query().in("id", ids).
                last("ORDER BY FIELD(id," + idStr + ")")
                .list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        //返回
        return Result.ok(shops);
    }

    private static  final ExecutorService CACHE_REBUILDER_EXECUTOR= Executors.newFixedThreadPool(10);
    /**
     * 逻辑过期
     * @param id
     * @return
     */
    public Shop queryWithLogicalExpire(Long id){
        String key =CACHE_SHOP_KEY + id;
        //从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            //不存在直接返回
            return null;
        }
        //命中需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data =(JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //未过期，直接返回
            return shop;
        }
        //获取互斥锁
        String lockKey= LOCK_SHOP_KEY+id;
        //判断是否获取所成功
        boolean isLock = tryLock(lockKey);
        if (isLock) {
            //成功，开启独立线程实现缓存重建
            CACHE_REBUILDER_EXECUTOR.submit(()->{
                try{
                    //重建缓存
                this.shop2Redis(id,20L);}
                catch(Exception e){
                    throw new RuntimeException(e);
                }finally{
                    //释放锁
                    unLock(lockKey);
                }
            });
        }
        //失败返回商铺信息
        return shop;
    }
    //获取锁
    private boolean tryLock(String key){
        Boolean b = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(b);
    }
    //释放锁
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }


    public void shop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        //查询商铺信息
        Shop shop = getById(id);
        Thread.sleep(200);
        //封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //存入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }


    public Result base(Long id) {
        String key =CACHE_SHOP_KEY + id;
        //从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //存在直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        //判断是否命中空值
        if (shopJson != null) {
            //返回一个错误信息
            return Result.fail("店铺不存在");
        }
        //不存在根据id查询数据库
        Shop shop =getById(id);
        //不存在，返回错误
        if (shop == null) {
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return Result.fail("商铺不存在");
        }
        //存在，写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //返回
        return Result.ok(shop);
    }
}
