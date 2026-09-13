package com.jacey.game.db.entity

import com.jacey.game.common.framework.mongo.DbDocument

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