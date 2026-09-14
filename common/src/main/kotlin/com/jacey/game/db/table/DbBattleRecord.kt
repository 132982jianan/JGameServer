package com.jacey.game.db.table

import com.jacey.game.common.framework.mongo.DbDocument
import java.util.Date

data class DbBattleRecord(
    override val _id: Int = 0,
    var battleType: Int = 0,
    var battleId: String? = null,
    var userIdList: String? = null,
    var battleStartTimestamp: Date? = null,
    var battleEndTimestamp: Date? = null,
    var turnCount: Int = 0,
    var winnerUserId: Int = 0,
    var gameOverReason: Int = 0,
) : DbDocument<Int> {
    companion object { const val COLLECTION_NAME = "battle_record" }
}
