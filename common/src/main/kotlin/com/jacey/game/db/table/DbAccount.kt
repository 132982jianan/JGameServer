package com.jacey.game.db.table

import com.jacey.game.common.framework.mongo.DbDocument
import com.jacey.game.db.AccountId
import com.jacey.game.db.LoginType
import java.util.Date

/** 账号主档。账号与角色严格分离；本项目不分区服，因此当前只有一个角色。 */
data class DbAccount(
    override val _id: String,
    val loginType: String = LoginType.LoginDebug.name,
    val loginName: String,
    val createTimestamp: Date = Date(),
    var lobbyId: Int? = null,
    var loginTimestamp: Date? = null,
    var logoutTimestamp: Date? = null,
    val playerList: MutableList<Player> = mutableListOf(),
) : DbDocument<String> {
    val accountId: AccountId get() = AccountId.createAccountIdByUserId(_id)

    data class Player(
        val playerId: Int,
        val createTimestamp: Date = Date(),
        var loginTimestamp: Date? = null,
    )
}
