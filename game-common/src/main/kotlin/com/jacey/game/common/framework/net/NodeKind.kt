package com.jacey.game.common.framework.net

/**
 * 节点类型枚举
 *
 * - gm:      GM/注册中心（原 game-gm-server）
 * - gateway: 网关（客户端长连接入口）
 * - logic:   逻辑服
 * - battle:  对战服
 * - chat:    聊天服
 */
enum class NodeKind {
    gm,
    gateway,
    logic,
    battle,
    chat,
}
