package com.hmdp.utils;

import cn.hutool.core.io.resource.ClassPathResource;
import cn.hutool.core.lang.UUID;
import lombok.val;
import org.apache.ibatis.javassist.ClassPath;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class SimpleRedisLock implements ILock {

    private String name;
    //StringRedisTemplate用于与 Redis 交互
    private StringRedisTemplate stringRedisTemplate;
    //锁的标识
    private static final String KEY_PREFIX = "lock:";
    //线程标识
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    //锁的脚本
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation((Resource) new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

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
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //获取锁
        Boolean success = stringRedisTemplate.opsForValue().
                setIfAbsent(KEY_PREFIX + name, threadId , timeoutSec, TimeUnit.SECONDS);
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



    /**
     *
     * 判断结束与释放锁之间是非原子操作，在多线程或分布式环境中，可能出现以下情况：
     * 线程 A 判断后，即将释放锁时，可能发生：
     *      产生阻塞（可能因为JVM垃圾回收）redis锁达到的过期时间阻塞仍未结束
     *      或者此时锁的过期时间到达，Redis 自动删除锁。
     *
     * 线程 B 重新获取锁并修改 id。
     * 线程 A 执行 delete，误删线程 B 的锁。
     */

    @Override
    public void unlock() {
        //调用Lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId()
                );

    }



//    @Override
//    public void unlock() {
//        //获取线程标识
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//        //获取锁中的标识
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        //判断标识是否一致
//        if (threadId.equals(id)) {
//            //一致释放锁
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
//
//    }
}


