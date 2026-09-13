package com.jacey.game.gateway.service

/**
 * 网关消息分区：rpcNum 区间 → 路由分区（与 rpc.proto 的号段分配约定一致）
 *
 * - 100-109      AUTH   注册/登录（未登录可访问）
 * - 110-199      LOGIC  逻辑类请求（匹配等，须登录）→ 主逻辑服
 * - 6000-6999    BATTLE 对战操作（须在对战中）→ battle 服
 * - 10001-14000  CHAT   聊天（须在对战中）→ chat 服
 *
 * 新增系统时按号段归入既有分区，或扩展新分区枚举；区间外
 * （含服务器推送号段 20001+）一律不受理客户端请求。
 */
enum class GatewayZone(val range: IntRange) {
    AUTH(100..109),
    LOGIC(110..199),
    BATTLE(6000..6999),
    CHAT(10001..14000);

    companion object {
        fun of(msgId: Int): GatewayZone? = values().firstOrNull { msgId in it.range }
    }
}
