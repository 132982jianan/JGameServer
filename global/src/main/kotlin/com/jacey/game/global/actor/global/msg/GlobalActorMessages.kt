package com.jacey.game.global.actor.global.msg

import com.jacey.game.common.msg.NetMessage

data class GlobalBattleCreated(
    val battleId: String,
    val battleServerId: Int,
    val playerIds: List<Int>,
)

data class GlobalBattleEnded(
    val battleId: String,
    val playerIds: List<Int>,
)

data class GlobalChatPush(
    val playerId: Int,
    val message: NetMessage,
)
