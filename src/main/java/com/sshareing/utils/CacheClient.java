package com.sshareing.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit timeUnit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,timeUnit);
    }
    public void setWithLogicalExpire(String key, Object value,  Long time, TimeUnit timeUnit){
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        //写入Redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData) );
    }

    public <R,ID> R queryWithPassThrough(String KeyPrefix, ID id, Class<R> type, Function<ID,R> dbFallBack, Long time, TimeUnit timeUnit){
        String key = KeyPrefix + id;
        //1.从redis 查询 商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        //判断命中是否为空值
        if(json != null)
            return null;
        //**实现缓存重建
        //*获取互斥锁
        R r = dbFallBack.apply(id);

        if(r == null){
            stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
       this.set(key,r,time,timeUnit);
        return r;
    }

    public <R,ID> R querywithLogicalExpire(String KeyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit timeUnit){
        String key = KeyPrefix + id;
        //1.从redis 查询 商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isBlank(json)) {
            //3.存在，直接返回
            R r1 = dbFallback.apply(id);
            if(r1 == null)
                return null;
            //写入Redis
            this.setWithLogicalExpire(key,r1,time,timeUnit);
           return r1;
//            return JSONUtil.toBean(json,R)
        }
        //命中，把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //未过期，返回
            return r;
        }
        //已过期，需要返回缓存重建
        //获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY+id;
        //获取锁是否成功
        boolean tryLock = tryLock(lockKey);
        if(tryLock) {
            //成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //重建缓存
                    //查询数据库
                    R r1 = dbFallback.apply(id);
                    //写入Redis
                    this.setWithLogicalExpire(key,r1,time,timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unLock(lockKey);
                }

            });
        }
        //返回过期商铺信息
        return  r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isFalse(flag);
    }

    private void unLock(String key){
        stringRedisTemplate.delete(key);

    }
}
