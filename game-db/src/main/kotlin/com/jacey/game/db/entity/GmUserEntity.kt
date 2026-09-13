package com.jacey.game.db.entity

import com.jacey.game.common.framework.mongo.DbDocument

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