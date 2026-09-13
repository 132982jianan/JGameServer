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