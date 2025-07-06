package com.hmdp.config;


import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissionConfig {
    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.40.129:6379").setPassword("north000");
        return Redisson.create(config);
    }


}
