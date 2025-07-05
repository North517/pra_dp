package com.hmdp.utils;

import lombok.val;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class SimpleRedisLock implements ILock {

    private String name;
    //StringRedisTemplate用于与 Redis 交互
    private StringRedisTemplate stringRedisTemplate;
    private static final String KEY_PREFIX = "lock:";

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }



    @Override
    public boolean trylock(Long timeoutSec) {
        //获取线程标识
        /**
         * 获取当前线程的唯一标识符（JVM 内唯一）。
         * 用途：作为锁的 value，用于后续释放锁时校验持有者身份（防止误释放其他线程的锁）。
         */
        long threadId = Thread.currentThread().getId();
        //获取锁
        Boolean success = stringRedisTemplate.opsForValue().
                setIfAbsent(KEY_PREFIX + name, threadId  + "", timeoutSec, TimeUnit.SECONDS);
        /**
         * 这样写本质上是为了防御 **null值风险 **
         *此处直接返回包装类或者基本类型都不行
         *
         * 在分布式系统中，Redis 操作可能因网络异常、超时或服务故障而失败，
         * 此时方法会返回 null 表示 “操作未成功执行”。
         *
         * 若返回包装类Boolean，则success 为 null 时会触发 NullPointerException
         * 若返回基本类型 boolean，则无法表达这种 “失败” 状态（只有TF）。
         */
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        //释放锁
    stringRedisTemplate.delete(KEY_PREFIX + name);
    }
}
