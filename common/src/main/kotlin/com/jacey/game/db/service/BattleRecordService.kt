package com.jacey.game.db.service

import com.jacey.game.common.framework.mongo.Mongo
import com.jacey.game.common.framework.mongo.MongoSequence
import com.jacey.game.db.table.DbBattleRecord

/**
 * 对战记录归档服务（object 单例）
 */
object BattleRecordService {
    private val collection get() = Mongo.db.getCollection(DbBattleRecord.COLLECTION_NAME, DbBattleRecord::class.java)

    suspend fun saveBattleRecord(battleRecord: DbBattleRecord) {
        val entity = if (battleRecord._id == 0)
            battleRecord.copy(_id = MongoSequence.nextId(DbBattleRecord.COLLECTION_NAME))
        else battleRecord
        collection.insertOne(entity)
    }
}
