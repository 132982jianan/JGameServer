package com.jacey.game.common.framework.mongo

import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReturnDocument
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.firstOrNull
import org.bson.Document

/**
 * 原子自增序列（counters 集合实现，替代 Mysql AUTO_INCREMENT）
 *
 * counters 文档结构：{ _id: "集合名", seq: 当前值 }
 * findOneAndUpdate($inc) 原子取号，供各实体生成自增 id。
 */
object MongoSequence {
    private val logger = KotlinLogging.logger {}
    private const val COLLECTION = "counters"

    /** 取下一个自增 id（原子操作，非阻塞） */
    suspend fun nextId(collectionName: String): Int {
        val counters: com.mongodb.kotlin.client.coroutine.MongoCollection<org.bson.Document> =
            Mongo.db.getCollection(COLLECTION)
        val result = counters.findOneAndUpdate(
            Filters.eq("_id", collectionName),
            Document("\$inc", Document("seq", 1)),
            FindOneAndUpdateOptions()
                .upsert(true)
                .returnDocument(ReturnDocument.AFTER)
        )
        @Suppress("UNCHECKED_CAST")
        val seq = (result?.get("seq") as? Number)?.toInt() ?: 1
        logger.debug { "nextId($collectionName) = $seq" }
        return seq
    }
}
