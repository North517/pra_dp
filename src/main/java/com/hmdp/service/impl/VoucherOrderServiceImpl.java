package com.hmdp.service.impl;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.log.Log;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.core.io.ClassPathResource;
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
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();


    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {
        //它是一个实现了 Runnable 接口的线程类，用于从 Redis Stream 中读取消息并处理订单，替代了原来的阻塞队列。
        private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
        String queueName = "stream.orders";

        //run 方法是线程执行的主逻辑，while(true) 表示无限循环，确保线程持续监听消息队列，永不退出。
        @Override
        public void run() {
            while (true) {
                try {
                    //1.获取消息队列中的订单信息
                    List<MapRecord<String,Object,Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"), // 定义消费者组g1，消费者c1
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),// 每次读取1条，阻塞2秒
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())// 从最后消费位置读取
                    );
                    //2.判断消息是否成功
                    if (list == null || list.isEmpty()) {
                        //2.1.如果获取失败，说明没有消息，继续下一次循环
                        continue;
                    }
                    //3.解析消息中的订单信息
                    MapRecord<String , Object , Object> record = list.get(0);
                    Map<Object,Object> value = record.getValue();//消息的具体内容（如 userId=123、voucherId=456 等），对应 Lua 脚本中 XADD 写入的键值对。
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(),true);//使用 Hutool 工具类将键值对映射为 VoucherOrder 对象
                    //4.如果获取成功，可以下单
                    handleVoucherOrder(voucherOrder);
                    //5.ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    //1.获取PendingList队列中的订单信息
                    List<MapRecord<String,Object,Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            // 关键修改：使用 "0-0" 作为读取偏移量
                            StreamOffset.create(queueName, ReadOffset.from("0-0"))
                    );
                    //2.判断消息是否成功
                    if (list == null || list.isEmpty()) {
                        //2.1.如果获取失败，说明PendingList没有消息，退出循环
                        break;
                    }
                    //3.解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //4.处理订单
                    handleVoucherOrder(voucherOrder);//
                    //5.ACK确认，存放已读取但未确认（.acknowledge）的消息，用于处理消费者故障恢复后重新消费未完成的消息。
                        //Redis 会将该消息从消费者组的「Pending List」（未确认消息列表）中移除，避免后续重复处理。
                        //若不确认，消息会留在 Pending List 中，消费者重启后会重新处理，可能导致订单重复创建。
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理PendingList订单异常", e);
                    // 短暂休眠，避免无限重试导致CPU占用过高
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException interruptedException) {
                        interruptedException.printStackTrace();
                    }
                }
            }
        }
    }

    //无限循环处理 Pending List 中的消息（已读取但未 ACK 的消息），直到所有未确认消息处理完毕。
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
        //获取用户id
        Long userId = UserHolder.getUser().getId();
        //获取订单id
        long orderId = redisIdWorker.nextId("order");
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );
        //2.判断结果时为0
        int r = result.intValue();
        if(r != 0){
            //2.1.不为0，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        //不需要把下单信息保存到阻塞队列了，lua脚本有直接的队列了

//        //2.2.为0，有购买资格，把下单信息保存到阻塞队列
//        VoucherOrder voucherOrder = new VoucherOrder();
//        voucherOrder.setId(orderId);
//        //2.4.用户id
//        voucherOrder.setUserId(userId);
//        //2.5.代金券id
//        voucherOrder.setVoucherId(voucherId);
//        //2.6.放入阻塞队列
//        orderTasks.add(voucherOrder);



        //3.获取代理对象（事务）
        proxy = (IVoucherOrderService)AopContext.currentProxy();

        //4.返回订单id
        return Result.ok(orderId);
    }


    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
//4.限制一人一单
        Long userId = voucherOrder.getUserId();
            //4.1查询订单
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

            save(voucherOrder);
        }
}