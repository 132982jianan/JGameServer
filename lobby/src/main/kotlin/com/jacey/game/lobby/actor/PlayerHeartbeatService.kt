package com.jacey.game.lobby.actor

import kotlin.random.Random

/** 与 reference code 相同：消息驱动，每 60~120 秒进入 minute 分支。 */
object PlayerHeartbeatService {
    const val SAVE_COOLDOWN_MILLIS: Long = 5 * 60 * 1000

    fun nextMinuteTimestamp(now: Long): Long = now + Random.nextLong(60_000, 120_001)
}
