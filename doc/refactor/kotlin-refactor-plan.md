# JGameServer Kotlin 化重构计划（供审核）

> 状态：待审核。通过后才开始编码。
> 参考仓库 `E:\01_gitee\slm_20260523\server\code`（下称 **code 项目**）只做参考，不改动其任何文件。
> 本仓库只改 `E:\03_github\JGameServer`。

---

## 0. 目标一句话

把 Spring Boot 多模块 Maven 工程改为 **Gradle + Kotlin 的单入口单 jar 工程**：去掉 Spring Boot，Akka 保留并由 Kotlin 协程包装（**actor 间通信同步写法、非阻塞执行**），HTTP 换 Ktor，注册/配置中心换 Nacos，Mongo/Redis 封装按 code 项目移植，部署脚本同步改造。

## 1. 已确认的决策（grill-me 面谈结论）

| # | 决策 | 结论 |
|---|------|------|
| 1 | Akka 版本 | **2.6.21 + scala 2.13**（Apache 2.0 末代线，classic API 兼容，支持 JDK 21；artery 配置平移） |
| 2 | 挂起语义 | Actor 处理器 `suspend` 化；挂起（等 Mongo/远端 actor）时**本 actor 新消息排队等待**，保留串行语义 |
| 3 | 包装层 API | 最小封装：`askAwait` / `tell` + `CoroutineActor` 抽象类；不搬 code 项目的 serve/stub DSL |
| 4 | 服务发现 | `*LoadBalance` Mongo 注册表迁 **Nacos naming**（akka 地址进实例 metadata），DAO/Service 退役 |
| 5 | GM HTTP | 路径/参数/JSON 契约**原样保留**，实现换 Ktor |
| 6 | 打包入口 | 新增 `server` 引导模块，`--kind {gm,logic,battle,chat,gateway} --id N` 分发；shadow 打**一个** fat jar |
| 7 | 构建骨架 | 照 code：`settings.gradle.kts` + buildSrc `java-conventions` + `libs.versions.toml`；Gradle 9.2.0 / Kotlin 2.3.0 / JDK 21 |
| 8 | 配置 | `resources/conf/` 五件默认配置；Nacos > 默认值；缺失自动 `publishConfig`；之后改配置只动 Nacos |
| 9 | TicTacToe-GUI | 移出构建（settings 不 include），目录保留 |
| 10 | 单例写法 | 全部 `object`，废除 `XxxManager.getInstance()`；`SpringManager`/`CoreManager` 删除，直接引用 object |
| 11 | proto | Gradle protobuf 插件（protoc 4.33.0 + kotlin builtin）**构建期自动生成**，运行时不再依赖 `protoc-3.12.0-rc-2-win64.zip` 手工生成；proto 源放 `game-common/src/main/proto/` |
| 12 | 重点 | 协程包装 Akka：同步写法、非阻塞 |

## 2. 现状盘点（改动对象的证据）

- 根 `pom.xml`：Spring Boot 2.0.2 parent、Java 8、akka 2.5.4_2.11、netty 4.1.13、jedis 2.9.0、spring-data-mongo。
- 6 个 Maven 模块 + TicTacToe-GUI；5 个服务各自 `SpringApplication.run` + fat jar。
- Akka 用法：classic `UntypedAbstractActor` + artery tcp + 自定义 serializer（`NetMessageSerializer`/`RemoteMessageSerializer`，allow-java-serialization=off）；actor path 注册在 Mongo `*LoadBalance` 集合，gateway 靠查 Mongo 找对端。
- 配置：`propertyConfig.xml`+`config.properties`（每服务一套，业务项少而分散）、`application.conf`（akka）、`application.yml`（mongo uri，env `MONGO_URI`）、`redis.properties`、`spring.xml`、`logback.xml`。
- GM HTTP：`/gm/gmUserLogin`、`/gm/executeGmCmd`、`/gateway`（ClientController 下发网关地址）；cookie+token 会话存 Redis；`GmUserSeedConfig` 启动种 admin 账号。
- DB：`game-db` DAO = MongoTemplate + spring-data-redis `ValueOperations`；实体 4 个（PlayUser/PlayState/GmUser/BattleRecord）+ BattleInfo；`counters` 集合自增 id。
- 客户端线协议（**不变**）：`packetLength | msgId | errorCode | protobuf body`，gateway 暴露 TCP 10001 + WebSocket。
- 部署：`deploy/docker-compose.yml`（mongo/redis + 5 个 jre8 容器挂 target jar）、`start-server.bat`（mvn 打包 → compose up）。

## 3. 目标结构（Gradle 多模块）

```
JGameServer/
├── settings.gradle.kts          # include 各模块；腾讯/阿里镜像 + mavenCentral；foojay resolver
├── gradle/libs.versions.toml    # 版本目录（版本对齐 code 项目）
├── gradle/wrapper/...           # Gradle 9.2.0（腾讯镜像 distributionUrl，同 code）
├── buildSrc/                    # java-conventions 预编译插件（JDK21 toolchain/UTF-8/opt-in）
├── game-common/                 # Kotlin。proto 源+生成、消息、编解码、协程akka包装、工具
├── game-db/                     # Kotlin。Mongo(DbCollection风格)+Redis(Lettuce协程)封装与DAO
├── game-gm-server/              # 各服务 = 启动逻辑 + 业务；不再有 main/Application
├── game-logic-server/
├── game-battle-server/
├── game-chat-server/
├── game-gateway-server/
├── server/                      # 唯一 main：kotlinx-cli 解析 --kind/--id，分发各 Start
└── deploy/                      # compose/bat 改造；TicTacToe-GUI 目录保留但不 include
```

模块依赖：`server → 各 *-server → game-db → game-common`。

## 4. 版本与依赖（对齐 code 项目 `libs.versions.toml`）

| 项 | 版本 | 说明 |
|---|---|---|
| Gradle / Kotlin / JDK | 9.2.0 / 2.3.0 / 21 | 与 code 相同（含 wrapper、foojay 0.8.0） |
| akka-actor/remote (2.13) | 2.6.21 | 替换 2.5.4_2.11；artery tcp 配置平移 |
| kotlinx-coroutines | 1.10.2 | 协程基础 |
| ktor (server-netty/core/config-yaml) | 3.3.3 | GM HTTP + 参考 code 的 `framework/ktor` 封装 |
| mongodb-driver-kotlin-coroutine | 5.5.1 | 替换 spring-data-mongo；bson-kotlinx 按 code 移植 |
| lettuce-core | 6.4.0 | 替换 jedis/spring-data-redis；协程 API + pub/sub |
| nacos-client(pure)/common/api | 3.1.0 | naming + config；`ConfigLoader` 移植 |
| protobuf gradle 插件 / runtime | 0.9.5 / 4.33.0 | 构建期生成 Java+Kotlin，去掉手工 protoc |
| netty-all | 4.1.112.Final | gateway TCP/WS |
| log4j2 2.24.1 + kotlin-logging 7.0.3 | — | 替换 logback/slf4j-lombok |
| shadow | 9.2.2 | server 模块打单 fat jar |
| kotlinx-cli / kaml | 0.3.6 / 0.61.0 | 入口参数 / yml 配置解析 |

删除依赖全家桶：spring-boot-*、spring-data-*、jedis、lombok、commons-configuration、reflectasm、jackson（redisTemplate 需要）、guava（确认无引用后）、poi（ImportExcelUtil 若无调用则删）。

## 5. 核心设计：协程包装 Akka（本次重点）

### 5.1 消息协议层（不变，搬进 game-common）

线协议 `packetLength|msgId|errorCode|protobuf`、`NetMessage`/`RemoteMessage` 语义、protobuf 序列化绑定全部保留，代码改 Kotlin。akka remote serializer 保留（NetMessage/RemoteMessage → 自定义 serializer，JDK 序列化仍关闭）。

### 5.2 包装层（game-common，新增 `coroutine/` 包）

```kotlin
// 1) 挂起式 ask：同步读感，不阻塞线程
suspend fun <T> ActorRef.askAwait(msg: Any, timeout: Duration = 5.s): T =
    suspendCancellableCoroutine { cont ->
        val reply = Patterns.ask(this, msg, timeout.toMillis().toInt()) // 内部调度，非阻塞
        // onComplete → cont.resume / cancel 时 future 取消
    }

// 2) CoroutineActor：子类处理器全部 suspend，内部单协程串行消费
abstract class CoroutineActor(
    actorRef: ActorRef,           // 真正的 akka actor（收 remote 消息进 mailbox）
    private val capacity: Int = Channel.UNLIMITED,
) {
    private val channel = Channel<Msg>(capacity, BufferOverflow.SUSPEND)

    // akka actor 收到消息 → channel.trySend（非阻塞，立即返回）
    // 独立协程（Dispatcher.Actor，limitedParallelism(1)）循环 channel.receive → suspend onReceive
    abstract suspend fun onReceive(msg: Any)
}
```

- **串行语义**：处理器挂起期间，新消息在 Channel 排队（Q2 决策），消息顺序 = mailbox 投递顺序；对战回合/匹配状态机不乱序。
- **非阻塞**：挂起点（Mongo/Lettuce/askAwait/delay）不占线程；线程池 = code 的 `Dispatcher`（Scheduler 小池 + Actor 大池）模式。
- **远程调用**：gateway→logic/battle/chat 的 artery 调用包装成 `suspend fun remoteAsk(...)`；`Patterns.ask` 的 future 用 `suspendCancellableCoroutine` 桥接。业务侧写法：

```kotlin
suspend fun forwardToLogic(msg: NetMessage) {
    val node = NodeDirectory.pick(NodeKind.logic) ?: return error(...)
    val reply = node.actorRef.askAwait(msg)   // 同步写法，非阻塞
    session.write(reply)
}
```

### 5.3 服务发现（Q4）

- 每节点启动：akka artery 地址（`akka://<sys>@host:port/user/xxxActor`）写入 Nacos 实例 metadata，`NodeRegister` 风格注册（自动 id 分配/指定 id 两模式，参照 code）。
- `NodeDirectory`（object）：订阅各 kind 变更 → `nodeId → ActorRef`（`actorSelection` 惰性 resolve + 缓存）。
- 删除：`GatewayServerLoadBalanceDAO/Service`、`BattleServer...`、`ChatServer...`、`LogicServer...` 全家；Mongo 只存业务数据。

### 5.4 启动序列（替换 Spring + CoreManager）

```kotlin
suspend fun start(kind: NodeKind, id: Int?): Boolean {
    Log4j2.init(kind)
    Exit.listenSignal()                 // TERM/INT → 倒序清理
    if (!Nacos.init()) return false     // nacos.yml 仅本地
    if (!NodeRegister.start(kind, id)) return false
    Redis.init()  &&  Mongo.init()      // 失败即退出
    Akka.start(kind, id)                // actorSystem + 各 CoroutineActor
    when (kind) { gateway -> Netty.start(...); gm -> Ktor.start(...) }
    return true
}
// server/Application.kt: main = 解析参数 → start(kind) → Exit.await()
```

各服务保留独立 `XxxStart.kt`（注册自己的 actor/路由），server 模块按 `--kind` 分发。

## 6. 配置中心（Q8 + 用户补充）

- `game-common/src/main/resources/conf/`：`nacos.yml`（仅本地引导）、`mongo.yml`、`redis.yml`、`net.yml`、`app.yml`。
- `ConfigLoader`（移植 code）：读 Nacos > 本地 `conf/` > classpath；**Nacos 无此配置时把默认值 publishConfig 发布**；`listen()` 支持热更新。现有分散配置归并：

| 旧 | 新 |
|---|---|
| application.yml `MONGO_URI` | `mongo.yml` `connectionString/databaseName` |
| redis.properties | `redis.yml` host/port/password |
| config.properties 各服务 | `net.yml`（端口段/akka 端口/ip 策略，NodeConfig 结构同 code NetConfig）+ `app.yml`（心跳 idle、各服务 id、connect.path 等业务项） |
| application.conf | 保留（akka 专用，代码内按配置生成或模板替换 artery host/port） |

- docker compose 通过环境变量注入 nacos 地址；`nacos.yml` 本地默认 `127.0.0.1:8848`。

## 7. Mongo / Redis 移植

- **Mongo**（game-db）：`mongodb-driver-kotlin-coroutine`；`DbCollection<Id, Data>`（CRUD/唯一索引/inc/set/push/pull）、`DbDocument`、`Instant` 编解码、counters 自增——全部按 code 的 `framework/mongo` + `bson-kotlinx` 移植。实体改 Kotlin data class（字段名/集合名不变，`PlayUserEntity` → `DbPlayUser` 风格，集合名保持原名）。
- **Redis**（game-db）：Lettuce 协程 API；`RedisKeyConstant/Helper` 保留改 Kotlin；DAO 方法改 `suspend`；GM token、sessionId↔gatewayId 绑定等逻辑不变；pub/sub 用于节点事件（如 code 的 RedisListener，若当前无使用则不引入）。
- DAO/Service 两层合并为 object 单例 Service（`GmUserService.findGmUserByUsername(...)` 直接调用），去掉 interface+impl 双层（无第二实现）。

## 8. GM HTTP（Ktor，Q5）

- 路由原样：`GET /gm/gmUserLogin`、`GET /gm/executeGmCmd`、`GET /gateway`（ClientController 的服务器列表下发）。
- 响应 JSON 结构（`ResultVO{code,msg,data}`）与 cookie token 机制不变；`ResultVO` 改 Kotlin data class + kotlinx-serialization。
- `RequestInterceptor`（登录态校验）→ Ktor `intercept`/pipeline；`GmUserSeedConfig` → 启动序列里的 `seedAdmin()`。
- 顺带：spring.xml、FilterConfig、FilterConfig/RequestInterceptor 的 Spring 注册全部删除。

## 9. 构建与部署改造

- proto：`doc/proto3/*.proto` 复制到 `game-common/src/main/proto/`，protobuf 插件构建期生成（Java+Kotlin builtin），`game-common/src/main/java/.../proto3/` 旧生成代码删除；运行时不再需要 zip 工具（用户决策 11）。
- `server` 模块：application 插件 + shadowJar（`Main-Class: server.ApplicationKt`），产物 `server-all.jar` 一个。
- compose：5 个服务同一镜像同一 jar，`command: java -jar server-all.jar --kind gateway --id 1`；基础镜像 `eclipse-temurin:21-jre`；加 `nacos` 服务（nacos/nacos-server，健康检查）；akka artery 端口与 `net.yml` 对齐；shm_size 可降（artery 仍保留）。
- `start-server.bat`：`gradlew :server:shadowJar` 替换 mvn；其余流程不变。
- IDE/杂项：`.gitignore` 加 `.gradle/`、`build/`；根 `pom.xml` 与各模块 `pom.xml` 删除。

## 10. Java→Kotlin 转换清单（按模块）

| 模块 | 处理 |
|---|---|
| game-common | 23 文件：消息/协议/annotation(MessageClassMapping 等)/utils 保留语义改 Kotlin；`ClassScanner` 保留（消息注册仍需扫包）或改显式注册表（倾向显式注册，删除反射扫描依赖） |
| game-db | 42 文件：entity→data class；DAO+Service 合并为 object；MongoSequenceGenerator→counters 实现 |
| 5 个 *-server | actor 改 CoroutineActor 子类（suspend onReceive）；Manager 改 object；`SpringManager.getBean` 调用点全部替换为 object 直引；Application/EventListener 删除 |
| 新增 | framework 层：`nacos/`（Nacos/ConfigLoader/Config）、`net/`（NodeRegister/NodeDirectory/NodeInfo）、`coroutine/`（askAwait/CoroutineActor/Dispatcher/Exit）、`mongo/`、`redis/`、`ktor/`（ResourceHandler 若 GM 需要静态页）——参照 code 文件逐一移植、适配 akka（code 无 akka，其 net/rpc 层仅作思路参考，不复制其 kotlinx-rpc 代码） |

注：code 的 `framework/rpc`（kotlinx-rpc）**不引入**——本项目跨节点通信走 akka artery，保持用户决策；只移植其 nacos/mongo/redis/ktor/process（Dispatcher/Exit/Log4j2）与 actor 串行化思路。

## 11. 实施顺序（编码阶段）

1. Gradle 骨架 + buildSrc + 版本目录 + wrapper；game-common 先行（proto 插件跑通生成）。
2. framework 层移植：nacos → process(Dispatcher/Exit/Log4j2) → coroutine/akka 包装（重点，含单元验证：两个 actor 挂起互发不阻塞线程、消息严格串行）。
3. mongo/redis 封装 + game-db 迁移。
4. 各服务 Start + actor/manager 改造（gateway → logic → battle → chat → gm）。
5. Ktor GM HTTP + Nacos naming 替换 LoadBalance。
6. server 入口 + shadow 打包 + compose/bat 改造。
7. 端到端验证（下节），清理 pom/logback/spring 残留。

## 12. 验证方案（Definition of Done）

- `gradlew :server:shadowJar` 产出一个 jar；`java -jar server-all.jar --kind X --id 1` 五种 kind 均可启动（本机 mongo/redis/nacos 起来后）。
- 客户端协议回归：TCP 10001 注册/登录/匹配/落子/聊天全链路通（可用现有 TicTacToe-GUI 或手工协议脚本验证——编码期定）。
- 配置发布：首次启动后 Nacos 控制台出现 `mongo/redis/net/app` 配置；改 Nacos 配置 → 热更新生效（listen 项）。
- 协程语义专项：并发 100 连接压 gateway，actor 消息无乱序（battle 回合断言）、线程数不随连接数增长。
- compose 一键起全套（含 nacos），GM HTTP 三接口行为与旧版一致（curl 对比 JSON）。

## 13. 风险与开放点

- akka 2.5→2.6：artery 配置兼容，但 serializer 配置需回归验证（NetMessage 跨节点序列化）。
- `ClassScanner` 若保留需支持 Gradle 产出的 jar 内扫描；倾向改为显式注册表（见 §10），若你希望保留注解扫描请在审核时说明。
- `ImportExcelUtil`/poi、`guava`、`jackson` 等若确无引用，直接删；有引用按需保留——编码第 1 步做引用普查。
- akka artery 在 Windows 本机的 canonical hostname 解析（原 `hostname = <getHostAddress>`）需在 net.yml 的 ip 策略里覆盖。

---

**请审核。通过后按 §11 顺序开工；任何一条要改，指出题号/小节号即可。**
