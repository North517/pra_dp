package com.hmdp.service.impl;
import cn.hutool.log.Log;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
//import cn.hutool.core.io.resource.ClassPathResource;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author north000_王大炮
 * @since 2025-7-8
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }


    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {

                try {
                    //1.获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //2.创建订单
                    handleVoucherOrder(voucherOrder);

                } catch (Exception e) {
                    log.error("处理订单异常",e);
                }


            }
        }
    }


    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //获取用户
        Long userId = voucherOrder.getUserId();
        //1.创建锁对象
        RLock lock = redissonClient.getLock("order:" + userId);
        //2.获取锁
        boolean isLock = lock.tryLock();

        if (!isLock) {
            //获取锁失败，返回失败
            log.error("不允许重复下单");
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        }finally {
            //释放锁
            lock.unlock();
        }

    }

    private IVoucherOrderService proxy;
    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );
        //2.判断结果时为0
        int r = result.intValue();
        if(r != 0){
            //2.1.不为0，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //2.2.为0，有购买资格，把下单信息保存到阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        //2.3.订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //2.4.用户id
        voucherOrder.setUserId(userId);
        //2.5.代金券id
        voucherOrder.setVoucherId(voucherId);
        //2.6.放入阻塞队列
        orderTasks.add(voucherOrder);

        //3.获取代理对象（事务）
        proxy = (IVoucherOrderService)AopContext.currentProxy();

        //4.返回订单id
        return Result.ok(orderId);
    }




    /**
     * “ @Transactional ” 的主要作用是简化 Spring 应用中的事务管理。通过在方法或类上添加该注解，
     * Spring 会自动为其创建事务边界，
     * <p>
     * 确保方法内的所有数据库操作要么全部成功（提交），要么全部失败（回滚）。
     */
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
//4.限制一人一单
        Long userId = voucherOrder.getUserId();
            //4.1查询订单
                       //query()是MyBatis-Plus提供的查询条件构造器入口方法
                       //.eq()第一个参数是数据库的字段名，第二个参数是要匹配的值。判断这两个参数是否相同
                       //多个.eq等与AND关系
            int count = query()
                    .eq("user_id", userId)
                    .eq("voucher_id", voucherOrder.getVoucherId())
                    .count();//.count()返回符合条件的记录数
            //4.2判断是否存在
            if (count > 0) {
                log.error("用户已购买，每位用户只限购买一次！");
                return;
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
                    .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)
                    .update();
            if (!success) {
                //失败
                log.error("扣除失败,库存不足");
                return;
            }


//6.扣除后创建订单
//            VoucherOrder voucherOrder = new VoucherOrder();
//            //6.1.订单id
//            long orderId = redisIdWorker.nextId("order");
//            voucherOrder.setId(orderId);
//            //6.2.用户id
//            voucherOrder.setUserId(userId);
//            //6.3.代金券id
//            voucherOrder.setVoucherId(voucherOrder);
            //6.直接调
            save(voucherOrder);
//            //7.放回订单id
//            return Result.ok(voucherOrder);
        }
}