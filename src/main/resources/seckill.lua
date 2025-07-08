---- 接收参数，分别为库存键、用户已下单集合键、用户ID
--
--local user_id = ARGV[2]
--local voucher_id = ARGV[1]
--
--local stock_key = 'seckill:stock' .. voucher_id
--local order_key = 'seckill:order' .. voucher_id
--
--
---- 判断库存是否充足
--local stock = tonumber(redis.call('GET', stock_key))
--if not stock or stock <= 0 then
--    return 1
--end
--
---- 判断用户是否已下单
--local is_member = redis.call('SISMEMBER', order_key, user_id)
--if is_member == 1 then
--    return 2
--end
--
---- 扣减库存
--redis.call('incrby', stock_key,-1)
---- 将用户ID存入集合
--redis.call('SADD', order_key, user_id)
--return 0


-- 1.参数列表
-- 1.1 优惠券id
local voucherId = ARGV[1]
-- 1.2 用户id
local userId = ARGV[2]

-- 2.数据key
-- 2.1 库存key   key 是优惠的业务名称加优惠券id  value 是优惠券的库存数
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2 订单key   key 也是拼接的业务名称加优惠权id  而value是用户id， 这是一个set集合，凡购买该优惠券的用户都会将其id存入集合中
local orderKey = 'seckill:order:' .. voucherId

-- 3.脚本业务
-- 3.1 判断库存是否充足 get stockKey
if (tonumber(redis.call('get', stockKey)) <= 0)  then  --将get的value先转为数字类型才能判断比较
    -- 3.2 库存不足，返回1
    return 1
end
-- 3.3 判断用户是否下单 sismember orderKey userId命令，判断当前key集合中，是否存在该value；返回1存在，0不存在
if (redis.call('sismember', orderKey, userId) == 1) then
    --3.4 存在说明是重复下单，返回2
    return 2
end
-- 3.5 扣库存
redis.call('incrby', stockKey, -1)
-- 3.6 下单（保存用户）
redis.call('sadd', orderKey, userId)
return 0
