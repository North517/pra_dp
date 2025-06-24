package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RedisData{
    private LocalDateTime expireTime;
    /**
     * 通过使用 Object 类型，可以将缓存层的实现与具体业务对象解耦。
     * 缓存工具类可以存储任意类型的对象，方便操作
     */
    private Object data;
}
