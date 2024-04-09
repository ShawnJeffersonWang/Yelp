package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.yaml.snakeyaml.events.Event;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {

    // 业务的名称，也就是锁的名称
    private final String name;
    private final StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    // 用的是静态常量和静态代码块，因此这个类一加载，这个脚本就已经初始化完成了，这样就不用每次释放锁就加载了
    static {
        // 可以接受字符串类型的脚本
        // 这种模式相当于硬编码，放文件里修改比较方便
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取线程的标识
//        long threadId = Thread.currentThread().getId();
        String threadId = ID_PREFIX + Thread.currentThread().threadId();
        // 获取锁
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        // 直接返回会有自动拆箱的过程, Boolean是包装类，boolean是基本类型
        // 做拆箱的时候要做好空指针的可能性
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        // 调用Lua脚本
        // RedisScript是一个类，而我们写的是一个文件，显然这个类就要加载这个文件，是每次释放锁的时候去读取这个文件
        // 还是提前把这个文件读取好，显然是提前读取好
        // 现在释放锁的代码变成一行代码了
        // 之前为什么出现线程安全问题，就因为他是两行代码，一个是查询一个是判断再然后是释放
        // 如果判断完毕，释放之前出现了阻塞，然后超时释放就会出现误删情况
        // 这行代码调的是Lua脚本，判断和删除是在脚本中执行的，是能够满足原子性的
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().threadId()
        );
    }
//    @Override
//    public void unlock() {
//        // 获取线程标识
//        String threadId = ID_PREFIX + Thread.currentThread().threadId();
//        // 获取锁中的标识
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        // 判断标识是否一致
//        if (threadId.equals(id)) {
//            // 释放锁
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
//    }
}
