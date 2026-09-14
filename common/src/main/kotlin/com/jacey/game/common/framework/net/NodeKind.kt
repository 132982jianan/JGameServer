package com.jacey.game.common.framework.net

/**
 * 节点类型枚举
 *
 * - portal:  HTTP 入口，只分配 Gate
 * - gate:    客户端长连接与消息路由
 * - lobby:   账号、玩家与大厅业务
 * - global:  匹配、战局目录、聊天与房间
 * - battle:  对战
 * - insight: 运维管理面
 *
 * @param actorName 节点主 actor 名（Nacos 注册的 actorPath 末段，各节点启动 XxxStart 时创建同名 actor）
 */
enum class NodeKind(val actorName: String) {
    portal("portalActor"),
    gate("gateActor"),
    lobby("lobbyActor"),
    global("globalActor"),
    battle("battleServerActor"),
    insight("insightActor"),
}
