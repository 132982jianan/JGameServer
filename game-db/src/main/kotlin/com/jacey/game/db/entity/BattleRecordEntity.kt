package com.jacey.game.db.entity

import com.jacey.game.common.framework.mongo.DbDocument
import java.util.Date

/**
 * 对战数据归档（Mongo 集合 battle_record）
 */
data class BattleRecordEntity(
    override val _id: Int = 0,
    /** 对战类型 */
    var battleType: Int = 0,
    /** 对战id */
    var battleId: String? = null,
    /** 对战用户id String 逗号分隔 */
    var userIdList: String? = null,
    /** 对战开始时间 */
    var battleStartTimestamp: Date? = null,
    /** 对战结束时间 */
    var battleEndTimestamp: Date? = null,
    /** 回合数 */
    var turnCount: Int = 0,
    /** 获胜方用户Id */
    var winnerUserId: Int = 0,
    /** 获胜原因 */
    var gameOverReason: Int = 0,
) : DbDocument<Int> {
    val id: Int get() = _id

    companion object {
        const val COLLECTION_NAME = "battle_record"
    }
}