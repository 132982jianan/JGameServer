package com.jacey.game.common.db

import com.jacey.game.common.framework.mongo.DbCollection
import com.jacey.game.common.framework.mongo.Mongo
import com.jacey.game.db.table.DbAccount
import com.jacey.game.db.table.DbPlayer

/** 多进程共享 Mongo 集合的唯一总入口，布局与参考 code 的 `common.db.Db` 一致。 */
object Db {
    lateinit var dbAccount: DbCollection<String, DbAccount>
        private set
    lateinit var dbPlayer: DbCollection<Int, DbPlayer>
        private set

    suspend fun start() {
        dbAccount = DbCollection.create(Mongo.db)
        dbPlayer = DbCollection.create(Mongo.db)
    }
}
