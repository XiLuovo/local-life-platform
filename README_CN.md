# 本地生活平台

[English README](README.md)

## 项目简介

这是一个 Spring Boot 后端项目，把外卖、门店、优惠券、秒杀和点评社交功能整合成一个本地生活平台。

## 模块结构

- `sky-common`：公共常量、异常、JWT、工具类
- `sky-pojo`：实体类、DTO、VO
- `sky-server`：控制器、业务逻辑、Mapper、配置、资源文件

## 核心功能

### 用户端

- 手机验证码登录 + JWT
- 门店和门店分类浏览
- 优惠券和秒杀券查看
- 外卖下单和支付
- 博客发布、点赞、关注
- 每日签到和连续签到统计

### 管理端

- 员工登录和管理
- 分类、菜品、套餐管理
- 订单管理和报表统计
- 普通优惠券创建
- 秒杀优惠券创建

## 技术亮点

- JWT 鉴权
- Redis 门店缓存
- Redis BitMap 签到
- Redis Set 关注关系
- Redis ZSet 点赞和动态流
- Redis Lua 原子抢券
- Redis Stream 异步秒杀下单，支持有限重试、`XCLAIM` 故障接管和 DLQ
- Lua 原子维护订单状态、消息确认与失败补偿，避免重复消费和错误回补
- WebSocket 订单提醒
- Mock 支付兜底

## 业务扩展

相比基础外卖系统，这个版本增加了：

- 门店模块：`tb_shop`、`tb_shop_type`
- 营销模块：`tb_voucher`、`tb_seckill_voucher`、`tb_voucher_order`
- 社交模块：`tb_blog`、`tb_follow`

## 环境要求

- MySQL：`localhost:3306/sky_take_out`
- Redis：`localhost:6379`，数据库 `10`
- 服务端口：`8080`

## 配置说明

本地真实配置文件是 `sky-server/src/main/resources/application-dev.yml`，仓库里保留的是模板文件 `application-dev.example.yml`。

你可以复制模板后填写自己的本地配置：

```text
sky-server/src/main/resources/application-dev.example.yml
-> sky-server/src/main/resources/application-dev.yml
```

JWT 密钥不再提供不安全的默认值，请在本地配置文件或环境变量中显式设置。仅本地联调时，可设置固定验证码：

```powershell
$env:SKY_JWT_ADMIN_SECRET="至少32位随机字符串"
$env:SKY_JWT_USER_SECRET="另一段至少32位随机字符串"
$env:SKY_AUTH_FIXED_LOGIN_CODE="123456"
```

验证码和 JWT 不会写入应用日志。

## 数据库初始化

仓库已经包含完整的基础表、营销表和社交表脚本，建议顺序：

1. 导入 `00-sky_take_out_base.sql`
2. 导入 `sky_take_out_local_life.sql`
3. 导入 `sky_take_out_social.sql`

对应文件：

- [00-sky_take_out_base.sql](sky-server/src/main/resources/db/00-sky_take_out_base.sql)
- [sky_take_out_local_life.sql](sky-server/src/main/resources/db/sky_take_out_local_life.sql)
- [sky_take_out_social.sql](sky-server/src/main/resources/db/sky_take_out_social.sql)

## Docker 一键启动

```powershell
Copy-Item .env.example .env
docker compose up --build -d
```

启动后：

- 服务：`http://localhost:8080`
- Knife4j：`http://localhost:8080/doc.html`
- MySQL：`localhost:13306/sky_take_out`
- Redis：`localhost:16379`

查看状态和日志：

```powershell
docker compose ps
docker compose logs -f app
```

如需重新执行初始化 SQL：

```powershell
docker compose down -v
docker compose up --build -d
```

## 启动方式

```powershell
mvn -pl sky-server -am spring-boot:run
```

编译检查：

```powershell
mvn -q -pl sky-server -am -DskipTests compile
```

自动化测试：

```powershell
mvn -B -ntp test
```

秒杀集成测试使用 Testcontainers 自动启动隔离的 MySQL 8 和 Redis 7，因此运行测试时需要本机或 CI 提供 Docker。测试通过公开 HTTP 接口覆盖异步下单成功、一人一单、重试耗尽进入人工处理，以及崩溃消费者消息被 `XCLAIM` 接管。

冒烟测试：

```powershell
powershell -ExecutionPolicy Bypass -File .\smoke-test.ps1 -Code 123456
```

## 秒杀可靠性设计

秒杀请求不会同步写入数据库，而是先通过 Redis Lua 完成库存校验、一人一单校验、库存预扣、订单状态记录和 Stream 消息写入，再由后台消费者异步创建订单。

处理流程：

1. `POST /user/voucher-order/seckill/{id}` 返回订单 ID，并将订单状态原子记录为 `PENDING`
2. 消费者从 `stream.orders` 读取消息并创建数据库订单
3. 处理成功后原子更新为 `SUCCESS`、确认 Stream 消息并清理重试状态
4. 处理失败时按固定间隔进行有限次数重试，避免消息持续热循环
5. 消费者异常退出后，其他实例通过 `XPENDING + XCLAIM` 接管超过空闲时间的消息
6. 重试耗尽后，先查询数据库最终状态；只有明确的重复订单或库存不一致才自动补偿
7. 无法安全判断最终状态时不恢复库存、不释放资格，而是写入 DLQ 等待人工处理，避免错误回补造成超卖
8. 未执行补偿的不确定状态允许被迟到的数据库成功结果修正为 `SUCCESS`

客户端可通过以下接口查询异步处理结果：

```http
GET /user/voucher-order/{orderId}/status
```

状态说明：

- `PENDING`：已通过秒杀资格校验，正在异步创建订单
- `SUCCESS`：数据库订单创建成功
- `FAILED`：处理失败，可通过返回的 `message` 查看原因

状态接口会校验当前登录用户，不能查询其他用户的秒杀订单；Redis 状态丢失时会回源数据库确认结果。

相关配置可通过环境变量调整：

| 环境变量 | 默认值 | 说明 |
| --- | ---: | --- |
| `SKY_SECKILL_CONSUMER_NAME` | 当前主机名 | 消费者名称前缀，每个 JVM 会自动追加 UUID |
| `SKY_SECKILL_MAX_RETRIES` | `3` | 单条消息最大处理次数 |
| `SKY_SECKILL_RETRY_DELAY_MS` | `2000` | 当前消费者重试间隔，单位毫秒 |
| `SKY_SECKILL_CLAIM_IDLE_MS` | `30000` | 消息空闲多久后允许被其他消费者接管 |
| `SKY_SECKILL_BLOCK_TIMEOUT_MS` | `2000` | Stream 阻塞读取超时时间，单位毫秒 |

## 接口文档

- Knife4j：`http://localhost:8080/doc.html`

## 相关资料

- [接口总览](API_OVERVIEW.md)
