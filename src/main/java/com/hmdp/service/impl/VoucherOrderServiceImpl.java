package com.hmdp.service.impl;

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
import jakarta.annotation.Resource;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 订单新增和库存扣减涉及到两张表的操作，这种情况最好加上事务
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1. 查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2. 判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀尚未开始!");
        }
        // 3. 判断秒杀是否已经结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束!");
        }
        // 4. 判断库存是否充足
        if (voucher.getStock() < 1) {
            // 库存不足
            return Result.fail("库存不足!");
        }
        // 加锁的对象是userId，所以需要在外面获取用户id, 然后上锁
        // 函数执行完，说明新的订单一定是写入数据库了，因为事务提交了，事务提交完再来释放锁
        Long userId = UserHolder.getUser().getId();
//        synchronized (userId.toString().intern()) {
//            // 这里这个this指向的是当前的VoucherOrderServiceImpl对象, 而不是他的代理对象
//            // 事务要想生效其实是因为Spring对当前这个VoucherOrderServiceImpl类做了动态代理，拿到了他的代理对象
//            // 用它来做事务处理，而现在这个this指向的是非代理对象，也就是目标对象，是没有事务功能的
//            // 是Spring失效的几种可能性之一
//
//            // 获取代理对象(事务)，就是IVoucherOrderService这个接口
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }
        // 相比synchronized会复杂，因为要手动创建锁释放锁
        // 创建锁对象
        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        // 获取锁
        boolean isLock = lock.tryLock(1200L);
        // 判断是否获取锁成功
        if (!isLock) {
            // 获取锁失败，返回错误或重试
            return Result.fail("不允许重复下单");
        }
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }

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
}
