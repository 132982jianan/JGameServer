package com.jacey.game.db.redis

/**
 * Redis 键封装（原 RedisKeyConstant/Helper 合并）
 *
 * Redis 只保留确实需要 TTL 的 Insight 登录令牌。
 * 在线玩家、匹配、战局路由和战斗状态全部由 ActorState 持有。
 */
object RKeys {
    // Insight/GM
    const val GM_USER_TOKEN = "gmUserToken"
}

