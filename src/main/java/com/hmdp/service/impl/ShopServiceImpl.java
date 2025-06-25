package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.val;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author north000_王大炮
 * @since 2025-6-23
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
        //如果未命中,判断命中的值是否为空
        if (shopJson != null) {
            return Result.fail("店铺不存在！");
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
        Shop shop = getById(id);
        //5.若不存在，放回404
        if (shop == null) {
            //防止存储穿透，使用存储空对象的方法，将空值写入reids
            stringRedisTemplate.opsForValue().set(key,"", CACHE_NULL_TTL, TimeUnit.MINUTES);
            //返回错误信息
            return Result.fail("店铺不存在");
        }
        //6.若存在，将商铺数据写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //7.返回商铺信息
        return Result.ok(shop);
    }

    @Override
    //@Transactional(rollbackFor = Exception.class)
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("商铺id不能为空");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+ id);
        return Result.ok();
    }
}
