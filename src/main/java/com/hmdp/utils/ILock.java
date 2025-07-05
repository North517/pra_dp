package com.hmdp.utils;

public interface ILock {

    /**
     * 尝试获取锁
     * timeoutSec 锁持有的超时时间，过期后会自动释放
     * true代表获取锁成功，false表示获取锁失败
     */
    boolean trylock(Long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();


}
