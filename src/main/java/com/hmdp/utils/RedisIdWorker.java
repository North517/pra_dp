package com.hmdp.utils;


import com.hmdp.entity.Shop;
import lombok.val;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    /*
    * 开始时间戳
    * */
    private static final long BEGIN_TIMESTAMP = 1735689600L;
    /*
     * 序列号的位数
     * */
    private static final int COUNT_BITS = 32;

    private StringRedisTemplate stringRedisTemplate;
    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long nextId(String keyPrefix) {
        //1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSeconds = now.toEpochSecond(ZoneOffset.UTC);
         long timestamp= nowSeconds - BEGIN_TIMESTAMP;
        //2.生成序列号
            //2.1.获取当前日期，精确到天
        String data = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
            //2.2.自增长
        long count = stringRedisTemplate.opsForValue().increment("icr"+ keyPrefix + ":"+ data);
        //3.拼接并返回
        return timestamp << COUNT_BITS | count;
    }

    public static void main(String[] args) {
        LocalDateTime time = LocalDateTime.of(2025, 1, 1, 0, 0);
        long second = time.toEpochSecond(ZoneOffset.UTC);
        System.out.println("second:" + second);

    }

}
