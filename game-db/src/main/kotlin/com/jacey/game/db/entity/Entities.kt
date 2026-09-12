package com.jacey.game.db.entity

import com.jacey.game.common.framework.mongo.DbDocument
import java.util.Date

/**
 * 玩家信息（Mongo 集合 play_user）
 * 字段与原 PlayUserEntity 完全一致，集合名不变。
 */
data class PlayUserEntity(
    override val _id: Int,
    /** 玩家名称 */
    var username: String? = null,
    /** 玩家昵称 */
    var nickname: String? = null,
    /** MD5加密密码 */
    var passwordMD5: String? = null,
    /** 注册时间戳 */
    var registTimestamp: Date? = null,
    /** 注册ip */
    var registIp: String? = null,
    /** 最后一次登录时间 */
    var lastLoginTimestamp: Date? = null,
    /** 最后一次登录ip */
    var lastLoginIp: String? = null,
) : DbDocument<Int> {
    val userId: Int get() = _id

    companion object {
        const val COLLECTION_NAME = "play_user"
    }
}

/**
 * 玩家状态（Mongo 集合 play_state）
 */
data class PlayStateEntity(
    override val _id: Int,
    var userId: Int = 0,
    /** 在线状态（在线or离线） */
    var userOnlineState: Int = 0,
    /** 行为状态（none or 匹配中 or 对战中 等） */
    var userActionState: Int = 0,
    /** 对战类型（1v1 or ..） */
    var battleType: Int = 0,
    /** 对战id */
    var battleId: String? = null,
) : DbDocument<Int> {
    val id: Int get() = _id

    companion object {
        const val COLLECTION_NAME = "play_state"
    }
}

/**
 * GM账户（Mongo 集合 gm_user）
 */
data class GmUserEntity(
    override val _id: Int,
    var username: String? = null,
    var passwordMD5: String? = null,
) : DbDocument<Int> {
    val userId: Int get() = _id

    companion object {
        const val COLLECTION_NAME = "gm_user"
    }
}

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
