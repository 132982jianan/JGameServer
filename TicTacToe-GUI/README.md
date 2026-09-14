# TicTacToe-GUI

Swing 测试客户端，与 JGameServer 服务端一起使用，要求 JDK 21。

## 连接方式

1. 向 Portal 的 `GET /gate` 请求节点地址。
2. 读取响应中的 `websocketEndpoint`（默认 `ws://127.0.0.1:25002/websocket`）。
3. WebSocket 握手成功后，使用二进制帧收发游戏消息；不提供原生 TCP 连接。

每帧包含一条 `packetLength | msgId | errorCode | protobuf body` 消息，最大 64 KiB。支持 `ws://` 和经过证书、主机名校验的 `wss://` 地址。登录后沿用 `Heartbeat` 协议发送心跳。

## 启动

先启动服务端，再在 JGameServer 仓库根目录运行：

```powershell
$env:PORTAL_HOST = '127.0.0.1'
$env:PORTAL_PORT = '8080'
.\gradlew.bat :TicTacToe-GUI:run
```

`PORTAL_HOST` / `PORTAL_PORT` 默认分别为 `127.0.0.1` / `8080`，它们指定 Portal HTTP 地址。

```powershell
.\gradlew.bat :TicTacToe-GUI:build
```
