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
- Redis Stream 异步秒杀下单
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

## 数据库初始化

建议顺序：

1. 准备外卖基础表
2. 导入 `sky_take_out_local_life.sql`
3. 导入 `sky_take_out_social.sql`

对应文件：

- [sky_take_out_local_life.sql](sky-server/src/main/resources/db/sky_take_out_local_life.sql)
- [sky_take_out_social.sql](sky-server/src/main/resources/db/sky_take_out_social.sql)

## 启动方式

```powershell
mvn -pl sky-server -am spring-boot:run
```

编译检查：

```powershell
mvn -q -pl sky-server -am -DskipTests compile
```

冒烟测试：

```powershell
powershell -ExecutionPolicy Bypass -File .\smoke-test.ps1
```

## 接口文档

- Knife4j：`http://localhost:8080/doc.html`

## 相关资料

- [接口总览](API_OVERVIEW.md)
