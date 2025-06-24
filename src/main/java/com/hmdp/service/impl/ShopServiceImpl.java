package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
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
    //缓存穿透
    //    Shop shop = queryWithPassThrough(id);
    //互斥锁解决缓存击穿
        Shop shop = queryWithMutex(id);
        //防止给前端返回null
        if (shop==null){
            return Result.fail("店铺不存在！");
        }


        return Result.ok(shop);
    }



//缓存击穿————互斥锁解决
    public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1.从redis里查缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否命中
        if (StrUtil.isNotBlank(shopJson)) {
            //3.命中，返回商铺信息
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //如果未命中,判断命中的值是否为空
        if (shopJson != null) {
            return null;
        }
        //4.实现存储重建
        //4.1.获取互斥锁
        String lockKey = "lock:shop:" + id;
        Shop shop = null;
        try {
            boolean isLock = tyrlock(lockKey);
            //4.2.判断是否成功
            if (!isLock) {
                //4.3失败，休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            //4.4.获取锁成功，再次检测redis缓存是否存在————DoubleCheck
                //1.从redis里查缓存
                shopJson = stringRedisTemplate.opsForValue().get(key);
                //2.判断是否命中
                if (StrUtil.isNotBlank(shopJson)) {
                    //3.命中，返回商铺信息
                    return JSONUtil.toBean(shopJson, Shop.class);
                }
                //如果未命中,判断命中的值是否为空
                if (shopJson != null) {
                    return null;
                }
            //4.5.成功，根据id查询数据库
            shop = getById(id);
                //模拟重建延时
            Thread.sleep(200);
            //5.若id查询数据库不存在，放回404
            if (shop == null) {
                //防止存储穿透，使用存储空对象的方法，将空值写入reids
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                //返回错误信息
                return null;
            }
            //6.若存在，将商铺数据写入redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //7.释放互斥锁
            unlock(lockKey);
        }
        //8.返回商铺信息
        return shop;
    }

//缓存穿透————返回空值法解决
    public Shop queryWithPassThrough(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1.从redis里查缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否命中
        if (StrUtil.isNotBlank(shopJson)) {
            //3.命中，返回商铺信息
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //如果未命中,判断命中的值是否为空
        if (shopJson != null) {
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
        Shop shop = getById(id);
        //5.若不存在，放回404
        if (shop == null) {
            //防止存储穿透，使用存储空对象的方法，将空值写入reids
            stringRedisTemplate.opsForValue().set(key,"", CACHE_NULL_TTL, TimeUnit.MINUTES);
            //返回错误信息
            return null;
        }
        //6.若存在，将商铺数据写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //7.返回商铺信息
        return shop;
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
