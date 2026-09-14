package com.jacey.game.db.table

import com.jacey.game.common.framework.mongo.DbDocument

data class DbGmUser(
    override val _id: Int,
    var username: String? = null,
    var passwordMD5: String? = null,
) : DbDocument<Int> {
    companion object { const val COLLECTION_NAME = "gm_user" }
}
