package com.hmdp.utils;

public interface Ilock {

    /**
     * 尝试获取锁
     * @param timeoutSec 超时时间，单位秒,过期后自动释放
     * @return  true代表获取锁成功，false代表获取锁失败
     */

    boolean tryLock(long timeoutSec);

    //释放锁
    void unlock();
}
