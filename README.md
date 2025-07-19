该仓库作为本地生活服务类应用的后端项目，涉及多方面的技术知识点（java、redis、springboot、mybatis-plus、lua、消息队列等），总结如下：

1. **核心功能**：
   - 商户查询与优惠券发放
   - 用户登录（短信验证码/JWT）
   - 秒杀系统（含分布式锁、库存扣减）
   - 点评与点赞功能
   - 关注/粉丝关系管理
2. **项目重点**：
   - 企业级开发流程
   - 高并发解决方案（如Redis缓存、分布式锁）
   - 事务与数据一致性

## 🛠 技术栈

### 后端技术

|     技术      |        用途        |  版本  |
| :-----------: | :----------------: | :----: |
|  SpringBoot   |      基础框架      | 2.7.x  |
| MyBatis-Plus  |      ORM框架       | 3.5.x  |
|     Redis     | 缓存/秒杀/分布式锁 | 6.2.22 |
|     MySQL     |      主数据库      |  8.0   |
|   RabbitMQ    |    异步消息队列    |  3.9   |
| Elasticsearch |     搜索与推荐     |  7.17  |
|      JWT      |      认证授权      |  3.19  |



### **一、ORM 与数据库设计**

1. **实体类设计与数据库映射**
   - 使用 MyBatis-Plus 的注解实现实体与数据库表的映射，如`@TableName`（指定表名，如`@TableName("tb_shop")`）、`@TableId`（指定主键及生成策略，如`IdType.AUTO`自增）、`@TableField(exist = false)`（标识非数据库字段，如`Shop.distance`用于临时存储距离信息）。
   - 实体类包含核心业务数据，如商铺（`Shop`）的名称、地址、评分，优惠券（`Voucher`）的支付金额、抵扣金额，用户（`User`）的手机号、密码等，覆盖本地生活服务的核心实体关系。
2. **MyBatis-Plus 应用**
   - 服务层通过继承`ServiceImpl<Mapper, Entity>`实现基础 CRUD 操作（如`ShopServiceImpl`继承`ServiceImpl<ShopMapper, Shop>`），简化数据库操作代码。
   - 支持分页查询（如`Page`对象的使用），适应商铺列表、优惠券列表等分页场景。

### **二、缓存策略与 Redis 应用**

1. **缓存问题解决方案**
   - **缓存穿透**：通过缓存空值（如`queryWithPassThrough`方法中，对不存在的商铺 ID 存储空字符串到 Redis，设置短期过期时间`CACHE_NULL_TTL`），避免频繁访问数据库。
   - 缓存击穿
     - 互斥锁方案：通过 Redis 的`setIfAbsent`实现分布式锁（`tryLock`方法），确保并发场景下只有一个线程重建缓存，其他线程等待重试。
     - 逻辑过期方案：缓存数据时附加过期时间（`RedisData`类包含`expireTime`），过期后不立即删除，而是通过独立线程池（`CACHE_REBUILD_EXECUTOR`）异步重建，避免缓存失效瞬间的数据库压力。
2. **Redis 工具类封装**
   - `CacheClient`封装缓存查询、写入、过期时间设置等通用操作，简化业务代码中缓存逻辑的调用（如`queryWithLogicalExpire`方法）。
   - 使用 JSON 序列化（`JSONUtil.toJsonStr`）实现对象与 Redis 字符串的转换，便于数据存储和读取。

### **三、分布式锁与并发控制**

1. **分布式锁实现**
   - 基于 Redis 的`setIfAbsent`命令实现分布式锁（`SimpleRedisLock`），确保分布式环境下的并发安全（如缓存重建、秒杀库存扣减）。
   - 锁的释放通过删除 Redis 键实现，结合过期时间防止死锁（如`unlock`方法删除锁键）。
2. **线程池应用**
   - 定义`CACHE_REBUILD_EXECUTOR`线程池（`Executors.newFixedThreadPool(10)`），用于异步执行缓存重建任务，避免阻塞主线程，提升系统响应速度。

### **四、高并发场景处理（秒杀与库存）**

1. **秒杀功能设计**
   - 秒杀优惠券（`SeckillVoucher`）通过 Redis 预扣减库存，结合 Lua 脚本保证库存检查和扣减的原子性，防止超卖。
   - 订单表（`VoucherOrder`）记录秒杀订单信息，包含支付方式、订单状态等字段，支持后续支付、核销流程。
2. **库存与订单一致性**
   - 秒杀过程中先通过 Redis 检查并扣减库存，再异步创建订单（可能结合消息队列），平衡性能与数据一致性。

### **五、事务与数据一致性**

- **事务同步**：商铺更新时（`ShopServiceImpl.update`），通过`TransactionSynchronizationManager`注册事务提交后的回调，确保数据库更新成功后再删除缓存，避免缓存与数据库数据不一致。
- **分布式事务**：秒杀场景下通过 “Redis 预扣减 + 异步确认” 模式，弱化强一致性要求，优先保证性能，通过后续补偿机制处理异常。

### **六、业务模块设计**

1. **核心业务模块**

   ![image-20250719192013828](assets/image-20250719192013828.png)

   - **商铺管理**：支持商铺查询（按 ID、类型）、更新，结合缓存提升查询性能。
   - **优惠券与秒杀**：区分普通优惠券（`Voucher`）和秒杀优惠券，支持优惠券列表查询、秒杀下单。
   - **用户互动**：通过`Follow`（关注）、`Blog`（探店笔记）、`BlogComments`（评论）实现社交功能，可能涉及 Redis 的集合结构（如存储用户关注列表）。

2. **地理位置功能**

   - `Shop`实体包含经纬度（`x`、`y`），结合 Redis 的 GEO 命令（如`GEORADIUS`）实现 “附近的商铺” 查询，满足本地生活服务的 LBS（基于位置的服务）需求。

### **七、代码规范与可维护性**

- **常量集中管理**：通过`RedisConstants`等类统一管理 Redis 键前缀（如`CACHE_SHOP_KEY`）、过期时间（如`CACHE_SHOP_TTL`），避免硬编码。
- **异常处理**：业务逻辑中通过`Result`统一返回结果，便于前端处理成功 / 失败状态（如`queryById`返回`Result.ok(shop)`或`Result.fail`）。



## 📚 学习资源

- [Redis实战文档](https://redis.io/docs/)

- [Elasticsearch官方指南](https://www.elastic.co/guide/)

  

综上，该项目围绕本地生活服务场景，整合了 ORM 框架、分布式缓存、高并发处理、分布式锁等核心技术，是 Java 后端开发中 “缓存优化”“分布式系统”“高并发设计” 等知识点的典型实践。