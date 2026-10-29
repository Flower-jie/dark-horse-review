package com.hmdp.utils;

import cn.hutool.core.io.resource.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements Ilock {

    private StringRedisTemplate stringRedisTemplate;
    private String name;



    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate,String name) {
        this.stringRedisTemplate = stringRedisTemplate;

        this.name= name;
    }
    private static final String LOCK_KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString()+"-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation((Resource) new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        //获取线程标识
        String threadId = ID_PREFIX+Thread.currentThread().getName();
        //获取锁
        Boolean success=stringRedisTemplate.opsForValue().setIfAbsent(LOCK_KEY_PREFIX+name,threadId+"",timeoutSec, TimeUnit.SECONDS);
        //判断是否获取锁成功
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock(){
        //调用lua脚本
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(LOCK_KEY_PREFIX+name),
                ID_PREFIX+Thread.currentThread().getName());
    }


//    @Override
//    public void unlock() {
//
//        //获取线程标识
//        String threadId = ID_PREFIX+Thread.currentThread().getName();
//        //获取锁标识值
//        String id = stringRedisTemplate.opsForValue().get(LOCK_KEY_PREFIX+name);
//        //判断锁标识值是否一致
//        if(id.equals(threadId)){
//            //释放锁
//            stringRedisTemplate.delete(LOCK_KEY_PREFIX+name);
//        }
//
//    }
}
