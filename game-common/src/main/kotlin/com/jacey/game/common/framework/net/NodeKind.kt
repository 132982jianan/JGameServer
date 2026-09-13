package com.jacey.game.common.framework.net

/**
 * 节点类型枚举
 *
 * - gm:      GM/注册中心（原 game-gm-server）
 * - gateway: 网关（客户端长连接入口）
 * - logic:   逻辑服
 * - battle:  对战服
 * - chat:    聊天服
 *
 * @param actorName 节点主 actor 名（Nacos 注册的 actorPath 末段，各节点启动 XxxStart 时创建同名 actor）
 */
enum class NodeKind(val actorName: String) {
    gm("gmActor"),
    gateway("gatewayActor"),
    logic("logicServerActor"),
    battle("battleServerActor"),
    chat("chatServerActor"),
}
