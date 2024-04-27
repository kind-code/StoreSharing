package com.sshareing.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.sshareing.dto.Result;
import com.sshareing.entity.VoucherOrder;
import com.sshareing.mapper.VoucherOrderMapper;
import com.sshareing.service.ISeckillVoucherService;
import com.sshareing.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.sshareing.utils.RedisIdWorker;
import com.sshareing.utils.UserHolder;
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

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;


@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;
    /*
    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.查询
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2.判断秒杀是否开始
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
         return Result.fail("秒杀尚未开始!");
        }
        //判断是否结束
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已经结束!");
        }
        //判断库存是否充足
        if (voucher.getStock()<1) {
            return Result.fail("库存不足");
        }

        Long userId = UserHolder.getUser().getId();
//        synchronized(userId.toString().intern()){}
        //创建锁对象
//        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            //获取锁失败，返回错误信息 或  重试
            return Result.fail("不允许重复下单");
        }
        try {
            //获取代理对象（事务）
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        }finally {
            lock.unlock();
        }


    }

     */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    //执行lua脚本
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    private IVoucherOrderService proxy;

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VocherOrderHandler());
    }

    private class VocherOrderHandler implements Runnable{
        String queueName = "stream.orders";
        @Override
        public void run() {
            while (true){
                try {
                    //1.获取消息队列中的队列信息  XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //2.判断消息获取是否成功
                    if(list == null||list.isEmpty()){
                        //2.1如果获取失败，说明没有消息 继续下一次循环
                        continue;
                    }
                    //解析消息订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //3如果获取成功，则可以下单
                    handleVoucherOrder(voucherOrder);
                    //4.ACK确认 stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("订单异常处理",e);
                    handlePendingList();
                }
                //2.创建订单
            }
        }

        private void handlePendingList() {
            while (true){
                try {
                    //1.获取pending-list中的队列信息  XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    //2.判断消息获取是否成功
                    if(list == null||list.isEmpty()){
                        //2.1如果获取失败，说明pending-list没有消息 继续下一次循环
                        break;
                    }
                    //解析消息订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //3如果获取成功，则可以下单
                    handleVoucherOrder(voucherOrder);
                    //4.ACK确认 stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("pending-list异常处理",e);
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
                //2.创建订单
            }
        }

        /*
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    private class VocherOrderHandler implements Runnable{

        @Override
        public void run() {
            while (true){
                try {
                    //1.获取队列中的队列信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("订单异常处理",e);
                }
                //2.创建订单
            }
        }


     */
        private void handleVoucherOrder(VoucherOrder voucherOrder) {
            Long userId = voucherOrder.getUserId();
            RLock lock = redissonClient.getLock("lock:order:" + userId);
            boolean isLock = lock.tryLock();
            if(!isLock){
                log.error("用户重复下单");
                return;
            }
            proxy.createVoucherOrder(voucherOrder);
        }
    }
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long orderId = redisIdWorker.nextId("order");
        //调用Lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), UserHolder.getUser().getId().toString(),
                orderId.toString());
        //判断结果为0
        int value = result.intValue();
        if (value!=0) {
            //不为0 没有购买资格
            return Result.fail(value==1?"库存不足":"不能重复下单");
        }


        //获取代理对象（事务）
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //返回订单id
        return Result.ok(orderId);


    }
    /*
    @Override
    public Result seckillVoucher(Long voucherId) {

        //调用Lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), UserHolder.getUser().getId().toString());
        //判断结果为0
        int value = result.intValue();
        if (value!=0) {
            //不为0 没有购买资格
            return Result.fail(value==1?"库存不足":"不能重复下单");
        }

        //为0 有购买资格 把下单队列保存到阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        //TODO 保存到阻塞队列
        Long userId = UserHolder.getUser().getId();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        orderTasks.add(voucherOrder);
        //获取代理对象（事务）
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //返回订单id
        return Result.ok(orderId);


    }

     */

//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        boolean isLock = lock.tryLock();
//        if (!isLock) {
//            //获取锁失败，返回错误信息 或  重试
//            return Result.fail("不允许重复下单");
//        }
//        try {
//            //获取代理对象（事务）
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } catch (IllegalStateException e) {
//            throw new RuntimeException(e);
//        }finally {
//            lock.unlock();
//        }


    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder){
        //6.一人一单
//        Long userId = UserHolder.getUser().getId();
        Long userId = voucherOrder.getUserId();
        //6.1查询订单

        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherOrder).count();
        //6.2判断订单是否存在
        if(count > 0){
            //用户已经购买过
            log.error("用户已经购买过一次");
            return;
        }
        //扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock",0)
                .update();
        if(!success){
            log.error("库存不足");
            return;
        }

        //创建订单

        save(voucherOrder);
        //返回订单id
//        return Result.ok(orderId);

    }
}
