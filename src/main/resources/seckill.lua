-- 1.参数列表
-- 1.1 优惠券 id
local voucherId = ARGV[1]
-- 1.2 用户ID
local userId = ARGV[2]
-- 1.3 订单ID
local oredrId = ARGV[3]
-- 2.数据key
-- 2.1库存key
local stockKey = 'seckill:stock:'..voucherId
-- 2.2订单key
local orderKey = 'seckill:order:'..voucherId

-- 3.脚本业务
-- 3.1 判断库存是否充足 get stockKey
if(tonumber(redis.call('get',stockKey))<=0) then
 -- 库存不足 返回1
    return 1
 end

 -- 3.2 判断用户是否下单 SISMEMBER orderKey userId
 if(tonumber(redis.call('sismember',orderKey,userId)) == 1) then
    -- 存在，说明重复下单 返回2
    return 2
 end
 -- 3.4 扣库存 incrby stockKey -1
 redis.call('incrby',stockKey,-1)
 -- 3.5 下单（保存用户）sadd orderKey userId
 redis.call('sadd',orderKey,userId)
 -- 3.6 发送消息到队列中    XADD stream.order * k1 v1 k2 v2
 redis.call('xadd','stream.orders','*','userId',userId,'voucherId',voucherId,'id',oredrId)

 return 0