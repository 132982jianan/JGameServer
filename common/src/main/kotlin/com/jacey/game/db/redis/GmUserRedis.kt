package com.jacey.game.db.redis

import com.jacey.game.common.framework.redis.Redis

/** GM token Redis 操作 */
object GmUserRedis {
    suspend fun setGmUserToken(token: String, expire: Int) {
        Redis.commands.setex(Redis.key("gmUserToken:$token"), expire.toLong(), token)
    }

    suspend fun getGmUserToken(token: String): String? =
        Redis.commands.get(Redis.key("gmUserToken:$token"))
}