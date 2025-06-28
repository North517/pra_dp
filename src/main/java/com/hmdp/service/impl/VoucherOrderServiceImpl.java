package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.val;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author north000_王大炮
 * @since 2025-6-28
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;


    @Override
    @Transactional
    public Result seckillVoucher(Long voucherId) {

//1.查询优惠劵
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//2.判断是否可以秒杀
        LocalDateTime now = LocalDateTime.now();
    // 2.1.秒杀未开始
        if (now.isBefore(voucher.getBeginTime())) {
            return Result.fail("秒杀尚未开始");
        }

    // 2.2.秒杀已结束
        if (now.isAfter(voucher.getEndTime())) {
            return Result.fail("秒杀已经结束");
        }
//3.判断库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }
//4.扣减库存
   /**
    * 这里两次调用.update()
    * 不是同一个方法被调用两次，而是不同对象上的同名方法。
    *    第一次 .update()：服务层方法调用
    *         所属对象：seckillVoucherService，
    *         返回UpdateWrapper 对象（条件构造器），只是进入"更新模式"
    *    第二次 .update()：条件构造器方法调用
    *         所属对象：UpdateWrapper
    *         将之前构建的所有条件（setSql()、eq() 等）组合成完整 SQL。
    *         真正执行数据库更新，返回是否成功（boolean）。
    */

        boolean success = seckillVoucherService
                .update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .update();
        if (!success) {
            //失败
            return Result.fail("扣除失败");
        }
//5.扣除后创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
    //5.1.订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
    //5.1.用户id
        Long userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);
    //5.1.代金券id
        voucherOrder.setVoucherId(voucherId);

    save(voucherOrder);
//6.放回订单id

        return Result.ok(orderId);
    }
}
