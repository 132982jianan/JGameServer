## 概览

![概览](https://github.com/JaceyRx/JGameServer/blob/master/doc/img/game.png "概览")

## 模块组织结构

``` 
  JGameServer
  ├── game-common            --基础公共模块
  ├── game-db                --基础数据库操作模块
  ├── game-gm-server         --Gm服务器模块:作为各服务器的注册中心、提供服务器管理功能
  ├── game-gateway-server    --网关服务器模块：用于请求转发与权限控制，支持分布式部署
  ├── game-logic-server      --逻辑服务器模块：主要游戏业务功能所在处，支持分布式部署
  ├── game-chat-server       --聊天服务器模块：用于聊天数据处理，支持分布式部署
  └── game-battle-server     --对战服务器模块：对战相关请求处理，支持分布式部署
  ```
## 执行流程

1. 客户端通过 HTTP 请求从GM服务器获取空闲Gateway服务器连接地址
2. 客户端连接网关服务器，长连接由gateway网关服务器维护
3. Gateway服务器接收客户端请求后，根据协议的不同通过Akka Remote 发送给其他业务服务器处理 

## 协议说明

```
 ----------------消息协议格式---------------------
  packetLength | rpcNum | errorCode | body
     int          int       int       byte[]

协议由四部分组成，前三部分为协议头，用于描述消息，第四部分为消息主体
第一部分：packetLength  4字节 int 类型 用于描述这个数据包的长度
第二部分：rpcNum  4字节 int 类型 用于描述当前消息的协议类型
第三部分：errorCode 4字节 int 类型 用于描述消息的错误类型
第四部分：body n字节 byte[] 类型 用于存储经protobuf序列化过的消息主体
```

## 快速开始（Docker Compose 一键启动）

```
1. git clone https://github.com/JaceyRx/JGameServer.git
2. 启动 Docker Desktop（要求本机可用 docker compose）
3. 双击项目根目录 start-server.bat，脚本会自动完成：
   - Maven 编译并打包全部服务器模块（不打docker镜像）
   - 拉取 mongo/redis/jre 镜像并启动 docker compose 全部服务
4. GM HTTP: http://127.0.0.1:8080/gateway ，客户端TCP入口: 127.0.0.1:10001
```

说明：
- 持久化存储已从 Mysql 重构为 MongoDB（Spring Data `MongoTemplate`），由 compose 内 `mongo:4.4` 提供，默认库名 `jgame_server`
- 自增id由 `counters` 集合原子自增实现（替代 Mysql `AUTO_INCREMENT`）
- GM默认账户 `admin`（与原sql种子一致）由 `GmUserSeedConfig` 在gm启动时自动创建
- 各服务器以 `eclipse-temurin:8-jre` 容器直接挂载 `target/` 目录运行，不需要构建docker镜像
- 镜像源/连接串可在 `deploy/.env` 中调整
- 单机非Docker运行：本机自备 MongoDB 与 Redis 后，`java -jar game-gm-server/target/gmServer.jar` 等直接运行各模块 fat jar

## TODO List
- 独立的登录服务器
- 独立的注册中心
- Gm后台管理Web界面
- 事件驱动模型的实现

## Tips
 ```
 1. 开发环境是 JDK1.8 高于或低于该版本JDK可能会无法运行
 2. 请使用IDEA 打开项目
 ```
## 测试客户端地址
[TicTacToe-GUI](https://github.com/JaceyRx/TicTacToe-GUI "TicTacToe-GUI")

![客户端演示](https://github.com/JaceyRx/JGameServer/blob/master/doc/img/client.gif "客户端演示")
