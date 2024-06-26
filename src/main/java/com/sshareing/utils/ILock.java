package com.sshareing.utils;

public interface ILock {
    /**
     * 获取锁
     * @param timeoutSec
     * @return
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unLock();
}
