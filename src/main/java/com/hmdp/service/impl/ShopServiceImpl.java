package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.val;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

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
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1.从redis里查缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否命中
        if (StrUtil.isNotBlank(shopJson)) {
            //3.命中，返回商铺信息
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
        }
        //4.如果未命中，通过id查询数据库
        Shop shop = getById(id);
        //5.若不存在，放回404
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        //6.若存在，将商铺数据写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop));
        //7.返回商铺信息
        return Result.ok(shop);
    }
}
