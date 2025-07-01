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
import org.springframework.aop.framework.AopContext;
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
        /**
         * 使用 synchronized 关键字进行同步控制，
         * intern() 方法会返回字符串在字符串常量池中的唯一实例（会共享常量池中的同一实例），
         * 这样可以保证同一个用户 ID 对应的锁是唯一的，不同用户之间不会互相阻塞，
         * 从而控制同一用户的并发请求，避免同一用户同时发起多个秒杀请求导致数据混乱。
         * 比如，防止同一用户在极短时间内多次点击秒杀按钮，同时进入后续的订单创建逻辑，造成一人多单的情况。
         */


        /**
         * 使用代理对象AopContext.currentProxy()
         * Spring AOP 的工作机制:
         * Spring AOP 通过代理模式实现方法拦截。当你调用一个被 @Transactional、@Cacheable 等注解标记的方法时，
         * 实际上是通过 代理对象 调用的，而非原始对象。
         *
         *
         * AOP 代理是 Spring 实现切面编程的核心机制，
         * 通过动态代理技术在不修改原始代码的前提下为对象添加额外功能。
         */

        /**
         * 若不使用代理，如果直接调用 this.createVoucherOrder(voucherId) ，会绕过 Spring AOP 代理，
         * 导致事务无法正常起作用
         *
         * 若不使用代理，扣减库存和创建订单的操作可能 不在同一个事务中，
         * 导致数据不一致（例如库存扣减成功但订单创建失败）。
         * 使用代理后，Spring 会确保这两个操作在同一个事务中，要么全部成功，要么全部回滚。
         */



        Long userId = UserHolder.getUser().getId();
        synchronized (userId.toString().intern()) {
            //获取代理对象（事务）
        IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
        return proxy.createVoucherOrder(voucherId);
    }
}


    /**
     *  “ @Transactional ” 的主要作用是简化 Spring 应用中的事务管理。通过在方法或类上添加该注解，
     * Spring 会自动为其创建事务边界，
     *
     * 确保方法内的所有数据库操作要么全部成功（提交），要么全部失败（回滚）。
     */
    @Transactional
    public  Result createVoucherOrder(Long voucherId) {
//4.限制一人一单
        Long userId = UserHolder.getUser().getId();
            //4.1查询订单
                       //query()是MyBatis-Plus提供的查询条件构造器入口方法
                       //.eq()第一个参数是数据库的字段名，第二个参数是要匹配的值。判断这两个参数是否相同
                       //多个.eq等与AND关系
            int count = query()
                    .eq("user_id", userId)
                    .eq("voucher_id", voucherId)
                    .count();//.count()返回符合条件的记录数
            //4.2判断是否存在
            if (count > 0) {
                return Result.fail("用户已购买，每位用户只限购买一次！");
            }


//5.扣减库存
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


            /**乐观锁，直接使用stock顶替version
             * 乐观锁优化：核心问题是避免多个线程对库存修改，出现超卖
             *
             * 原始写法（gt("stock", voucher.getStock())）
             *       绝对安全：严格保证扣减的是查询时的库存，避免超卖
             *       并发冲突高：只要有其他线程修改过库存，当前线程就会失败
             *
             *修改后写法（gt("stock", 0)）
             *       并发成功率高：只要库存没卖完，就允许扣减
             *
             * 优先保证 “尽量卖完库存”，而非 “绝对不超卖”
             * （因为库存通常是提前设置好的，超卖会触发库存报警，业务上可人工处理）
             * 原始写法会导致大量请求因并发冲突失败，用户体验极差
             */
            boolean success = seckillVoucherService
                    .update()
                    .setSql("stock = stock - 1")
                    .eq("voucher_id", voucherId).gt("stock", 0)
                    .update();
            if (!success) {
                //失败
                return Result.fail("扣除失败");
            }


//6.扣除后创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            //6.1.订单id
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            //6.2.用户id
            voucherOrder.setUserId(userId);
            //6.3.代金券id
            voucherOrder.setVoucherId(voucherId);
            save(voucherOrder);
            //7.放回订单id
            return Result.ok(orderId);
        }




}