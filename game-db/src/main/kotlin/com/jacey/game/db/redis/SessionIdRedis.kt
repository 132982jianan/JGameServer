package com.jacey.game.db.redis

import com.jacey.game.common.framework.redis.Redis

/** Session 相关操作（自增 sessionId、userId<->sessionId 绑定） */
object SessionIdRedis {
    private val c get() = Redis.commands

    suspend fun addAndGetNextAvailableSessionId(): Long =
        c.incr(Redis.key(RKeys.SESSION_ID_AUTO_INCREASE)) ?: 0L

    suspend fun getOneUserIdToSessionId(userId: Int): Int? =
        c.hget(Redis.key(RKeys.USER_ID_TO_SESSION_ID), Redis.key(userId.toString()))?.toIntOrNull()

    suspend fun getAllUserIdToSessionId(): Map<Int, Int> {
        val result = LinkedHashMap<Int, Int>()
        c.hgetall(Redis.key(RKeys.USER_ID_TO_SESSION_ID)).collect { kv ->
            result[kv.key.str.toInt()] = (kv.value ?: "").toIntOrNull() ?: 0
        }
        return result
    }

    suspend fun setOneUserIdToSessionId(userId: Int, sessionId: Int) {
        c.hset(Redis.key(RKeys.USER_ID_TO_SESSION_ID), Redis.key(userId.toString()), sessionId.toString())
    }

    suspend fun removeOneUserIdToSessionId(userId: Int) {
        c.hdel(Redis.key(RKeys.USER_ID_TO_SESSION_ID), Redis.key(userId.toString()))
    }

    suspend fun getOneSessionIdToUserId(sessionId: Int): Int? =
        c.hget(Redis.key(RKeys.SESSION_ID_TO_USER_ID), Redis.key(sessionId.toString()))?.toIntOrNull()

    suspend fun setOneSessionIdToUserId(sessionId: Int, userId: Int) {
        c.hset(Redis.key(RKeys.SESSION_ID_TO_USER_ID), Redis.key(sessionId.toString()), userId.toString())
    }

    suspend fun removeOneSessionIdToUserId(sessionId: Int) {
        c.hdel(Redis.key(RKeys.SESSION_ID_TO_USER_ID), Redis.key(sessionId.toString()))
    }
}