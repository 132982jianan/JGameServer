package com.jacey.game.db.table

import com.jacey.game.common.framework.mongo.DbDocument
import java.util.Date

/** PlayerActor 独占的角色聚合根；定时保存时整体 replace/upsert。 */
data class DbPlayer(
    override val _id: Int,
    val accountId: String,
    val account: PlayerAccountData = PlayerAccountData(),
    val basic: PlayerBasicData = PlayerBasicData(),
    val inventory: PlayerInventoryData = PlayerInventoryData(),
) : DbDocument<Int> {
    val playerId: Int get() = _id
}

data class PlayerAccountData(
    var firstLoginTimestamp: Date? = null,
    var lastLoginTimestamp: Date? = null,
    var lastLogoutTimestamp: Date? = null,
    var lastLoginIp: String = "",
)

data class PlayerBasicData(
    var name: String = "",
    var level: Int = 1,
    var exp: Long = 0,
)

data class PlayerInventoryData(
    val items: MutableMap<Int, Long> = mutableMapOf(),
)
