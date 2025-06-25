package com.hmdp.utils;


import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import io.lettuce.core.RedisClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {


    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value,Long time, TimeUnit unit ) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    public void setWithLogicalExpire(String key, Object value,Long time, TimeUnit unit ) {
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }






    //缓存穿透————返回空值法解决
    public <R,ID>R queryWithPassThrough(
            String keyprefix ,
            ID id,
            Class<R> type,
            Function<ID,R> dbFallack,
            Long time,
            TimeUnit unit) {
        String key = keyprefix + id;
        //1.从redis里查缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否命中
        if (StrUtil.isNotBlank(json)) {
            //3.命中，返回商铺信息
            return JSONUtil.toBean(json,type);
        }
        //如果未命中,判断命中的值是否为空
        if (json != null) {
            return null;
        }
        /**
         *  此处!= null与== ""是一样的效果，
         * 因为前面的isNotBlank已经判断了只有null和""会未命中，返回flase
         * 都是在排除null，找到""解决缓存穿透的问题
         *         if (shopJson == "") {
         *             return Result.fail("店铺不存在！");
         *         }
         * */
        //4.如果未命中，通过id查询数据库
        R r = dbFallack.apply(id);
        //5.若不存在，放回404
        if (r == null) {
            //防止存储穿透，使用存储空对象的方法，将空值写入reids
            stringRedisTemplate.opsForValue().set(key,"", CACHE_NULL_TTL, TimeUnit.MINUTES);
            //返回错误信息
            return null;
        }
        //6.若存在，将商铺数据写入redis
        this.set(key, r, time, unit);
        //7.返回商铺信息
        return r;
    }



    //建立线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR =
            Executors.newFixedThreadPool(10);
    //缓存击穿————逻辑过期时间解决
    public <R,ID>R queryWithLogicalExpire(
            String keyprefix ,
            ID id,
            Class<R> type,
            Function<ID,R> dbFallack,
            Long time,
            TimeUnit unit) {
        String key = keyprefix + id;
        //1.从redis里查缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否命中
        if (StrUtil.isBlank(json)) {
            //3.未命中，返回null
            System.out.println("未命中cache");
            return null;
        }

        //4.命中，先把json反序列化为对象
        /**
         * 取出的是Object类型，需强转为JSONObject类型，而不是直接反序列化为 Shop 类型，
         * 因为反序列化之后redisData.getData()得到的数据已经是解析的 JSON 对象结构（通常由序列化库表示为 JSONObject）
         * 直接转成shop等自定义类，也可以，但可能造成泛型信息丢失：在运行时，Java 的泛型会经历类型擦除
         */

        //        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        //        JSONObject data = (JSONObject) redisData.getData();
        //        Shop shop = JSONUtil.toBean(data, Shop.class);
        //简化代码
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(),type);
        LocalDateTime expireTime = redisData.getExpireTime();

        //5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //5.1.未过期，直接返回店铺信息
            return r;
        }
        //5.2.已过期，重新建立缓存
        //6.缓存重建
        //6.1.获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tyrlock(lockKey);
        //再一次检测Redis缓存是否过期，做doublecheck ，若存在无需重建缓存
        if(expireTime.isAfter(LocalDateTime.now())){
            //.未过期，直接返回店铺信息
            return r;
        }
        //6.2.判断是否取锁成功
        if(isLock){
            // 6.3.成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //6.3.1查询数据库
                    R r1 = dbFallack.apply(id);
                    //6.3.2.写入redis
                    this.setWithLogicalExpire(key,r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }
        //6.4.返回过期的商铺信息
        return r;
    }









    //锁方法
    //上互斥锁
    private boolean tyrlock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    //解锁
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }





}
