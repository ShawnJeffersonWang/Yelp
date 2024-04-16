package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
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

    // 任务应该在类初始化完毕后立马执行
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {

        String queueName = "stream.orders";

        @Override
        public void run() {
            while (true) {
                try {
                    // 1. 获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 2. 判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        // 2.1. 如果获取失败，说明没有消息，继续下一次循环
                        continue;
                    }
                    // 3. 解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 4. 如果获取成功，可以下单
                    handleVoucherOrder(voucherOrder);
                    // 5. ACK确认 XACK stream.orders
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    // 1. 读取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS streams.order 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    if (list == null || list.isEmpty()) {
                        // 如果获取失败，说明pending-list没有异常消息，结束循环
                        break;
                    }
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }
    }

//    private final BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
//    private class VoucherOrderHandler implements Runnable {
//
//        @Override
//        public void run() {
//            while (true) {
//                try {
//                    // 1. 获取队列中的订单信息
//                    VoucherOrder voucherOrder = orderTasks.take();
//                    // 2. 创建订单
//                    handleVoucherOrder(voucherOrder);
//                } catch (Exception e) {
//                    log.error("处理订单异常", e);
//                }
//            }
//        }
//    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 用户id不能从userHolder去取，现在是从线程池获取的全新的线程而不是主线程
        // 从ThreadLocal去取这个用户是取不到的
        // 1. 获取用户
        Long userId = voucherOrder.getUserId();
        // 2. 创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 3. 获取锁
        boolean isLock = lock.tryLock();
        // 4. 判断是否获取锁成功
        if (!isLock) {
            // 异步处理不用返回给前端
            log.error("不允许重复下单");
            return;
        }
        // 事务代理对象是拿不到的，代理对象也是基于ThreadLocal获取的，子线程是没有办法去ThreadLocal取出想要的东西的
        // 只能事务对象提前获取 -> 在主线程获取
        try {
            // 异步处理，不再需要返回给前端任何东西
            proxy.createVoucherOrder2(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    private IVoucherOrderService proxy;

    // 订单新增和库存扣减涉及到两张表的操作，这种情况最好加上事务
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取用户
        Long userId = UserHolder.getUser().getId();
        // 2.3. 订单id
        long orderId = redisIdWorker.nextId("order");
        // 1. 执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        // 2. 判断结果是否为0
        int r = result.intValue();
        if (r != 0) {
            // 2.1. 不为0, 代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 3. 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 有两种方法，一种是也把他传入阻塞队列中，还有一种放到成员变量的位置
        // 4. 返回订单id
        return Result.ok(orderId);
    }
    // 订单新增和库存扣减涉及到两张表的操作，这种情况最好加上事务
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 获取用户
//        Long userId = UserHolder.getUser().getId();
//        // 1. 执行lua脚本
//        Long result = stringRedisTemplate.execute(
//                SECKILL_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(), userId.toString()
//        );
//        // 2. 判断结果是否为0
//        int r = result.intValue();
//        if (r != 0) {
//            // 2.1. 不为0, 代表没有购买资格
//            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
//        }
//        // 2.2. 为0, 有购买资格, 把下单信息保存到阻塞队列
//        VoucherOrder voucherOrder = new VoucherOrder();
//        // 2.3. 订单id
//        long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        // 2.4. 用户id
//        voucherOrder.setUserId(userId);
//        // 2.5. 代金券id
//        voucherOrder.setVoucherId(voucherId);
//        // 2.6. 放入阻塞队列
//        orderTasks.add(voucherOrder);
//        // 3. 获取代理对象
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//        // 有两种方法，一种是也把他传入阻塞队列中，还有一种放到成员变量的位置
//        // 4. 返回订单id
//        return Result.ok(orderId);
//    }

    // 订单新增和库存扣减涉及到两张表的操作，这种情况最好加上事务
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 1. 查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        // 2. 判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            // 尚未开始
//            return Result.fail("秒杀尚未开始!");
//        }
//        // 3. 判断秒杀是否已经结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已经结束!");
//        }
//        // 4. 判断库存是否充足
//        if (voucher.getStock() < 1) {
//            // 库存不足
//            return Result.fail("库存不足!");
//        }
//        // 加锁的对象是userId，所以需要在外面获取用户id, 然后上锁
//        // 函数执行完，说明新的订单一定是写入数据库了，因为事务提交了，事务提交完再来释放锁
//        Long userId = UserHolder.getUser().getId();
////        synchronized (userId.toString().intern()) {
////            // 这里这个this指向的是当前的VoucherOrderServiceImpl对象, 而不是他的代理对象
////            // 事务要想生效其实是因为Spring对当前这个VoucherOrderServiceImpl类做了动态代理，拿到了他的代理对象
////            // 用它来做事务处理，而现在这个this指向的是非代理对象，也就是目标对象，是没有事务功能的
////            // 是Spring失效的几种可能性之一
////
////            // 获取代理对象(事务)，就是IVoucherOrderService这个接口
////            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
////            return proxy.createVoucherOrder(voucherId);
////        }
//        // 相比synchronized会复杂，因为要手动创建锁释放锁
//        // 创建锁对象
////        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        // 获取锁
//        boolean isLock = lock.tryLock();
//        // 判断是否获取锁成功
//        if (!isLock) {
//            // 获取锁失败，返回错误或重试
//            return Result.fail("不允许重复下单");
//        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            // 释放锁
//            lock.unlock();
//        }
//    }

    /**
     * 事务的范围其实是更新数据库的范围（减库存操作和创建订单操作）而不是整个方法，前面只是查询，不用加事务
     * 在方法上加synchronized同步的锁是this，是当前对象，肯定是线程安全的，但这样不管是任何用户来了都要加这个锁
     * 而且都是同一把锁，整个方法就是串行执行了，性能很差
     * 同一个用户才判断并发安全问题，不是同一个用户不需要加锁
     */
    // 提交订单完了之后先释放锁才去提交事务，事务是被Spring管理的，事务的提交是在函数执行完以后，由Spring做的提交
    // 锁在大括号结束后已经释放了，锁释放了意味着其他线程已经可以进来了，而此时事务尚未提交
    // 如果此时有用户查询订单，新增的订单可能还没写入数据库，就有可能出现并发安全问题
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 新增数据只能判断是否存在，而不能判断是否修改过，只能用悲观锁
        // 5. 一人一单
        // 加锁应该是当前用户，以用户id加锁，减小锁定资源范围
        Long userId = UserHolder.getUser().getId();

        // 去字符串常量池找和这个值一样的字符串地址，这样就能确保用户的id一样，锁就一样
        // 5.1. 查询订单
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 5.2. 判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            return Result.fail("用户已经购买过一次");
        }

        // 6. 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock -1")
                // where id = ? and stock = ?
                // 不再判断库存是否与查到的相等，只要判断库存大于0就行
                // where id = ? and stock > 0
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update();
        if (!success) {
            // 扣减失败
            return Result.fail("库存不足!");
        }

        // 7. 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 7.1. 订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 7.2. 用户id
        voucherOrder.setUserId(userId);
        // 7.3. 代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        // 8. 返回订单id
        return Result.ok(orderId);
    }

    @Transactional
    public void createVoucherOrder2(VoucherOrder voucherOrder) {
        // 新增数据只能判断是否存在，而不能判断是否修改过，只能用悲观锁
        // 5. 一人一单
        // 加锁应该是当前用户，以用户id加锁，减小锁定资源范围
        // userId不能通过ThreadLocal获取，是异步的子线程，要从voucherOrder里面去取
//        bug: Long userId = UserHolder.getUser().getId();
        Long userId = voucherOrder.getUserId();
        // 去字符串常量池找和这个值一样的字符串地址，这样就能确保用户的id一样，锁就一样
        // 5.1. 查询订单
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        // 5.2. 判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            log.error("用户已经购买过一次！");
            return;
        }

        // 6. 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock -1")
                // where id = ? and stock = ?
                // 不再判断库存是否与查到的相等，只要判断库存大于0就行
                // where id = ? and stock > 0
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)
                .update();
        if (!success) {
            // 扣减失败
            log.error("库存不足！");
            return;
        }

        // 7. 创建订单
        save(voucherOrder);
        // 也不需要返回id了，业务是异步执行的
    }
}
