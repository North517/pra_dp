package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;

import javax.annotation.Resource;
import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_LIST_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    //private IShopTypeService shopTypeService;
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Override
    public Result queryShopType() {
        //1.从redis中查找
        String shoptype =stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_LIST_KEY);
        //2.判断reids是否存在数据，如果不为空，直接返回缓存中的信息
         if(StrUtil.isNotBlank(shoptype)){
             //2.1.存在，返回
             List<ShopType> shopTypes =JSONUtil.toList(shoptype,ShopType.class);
             //将string类型转化为List类型
             return Result.ok(shopTypes);
         }
         //2.2.redis中不存在，就从数据库中查找
        List<ShopType> shopTypes = query().orderByAsc("sort").list();

         //3.判断数据库中是否为空，直接返回
        if(shopTypes == null) {
            //3.1.数据库中也不存在，返回flase
            return Result.fail("未查找到商铺信息");
        }
        //3.2.数据库中存在，将数据缓存到Redis当中
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE_LIST_KEY, JSONUtil.toJsonStr(shopTypes));
        return Result.ok(shopTypes);
    }
}
