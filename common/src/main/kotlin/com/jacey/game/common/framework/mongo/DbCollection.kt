package com.jacey.game.common.framework.mongo

import com.mongodb.MongoException
import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import org.bson.conversions.Bson
import kotlin.reflect.KProperty1

/**
 * Mongo 集合的统一类型安全入口。
 *
 * 命名、集合名推导和全量 replace/upsert 语义与参考 code 的 DbCollection 保持一致：
 * `DbPlayer` 默认映射到 `Player`，业务代码统一通过 [com.jacey.game.common.db.Db] 访问。
 */
class DbCollection<Id : Any, Data : DbDocument<Id>>(
    database: MongoDatabase,
    private val collectionName: String,
    dataClass: Class<Data>,
) {
    companion object {
        inline fun <reified Id : Any, reified Data : DbDocument<Id>> create(
            database: MongoDatabase,
            collectionName: String = Data::class.simpleName!!.removePrefix("Db"),
        ): DbCollection<Id, Data> = DbCollection(database, collectionName, Data::class.java)
    }

    val collection = database.getCollection(collectionName, dataClass)
    private val logger = KotlinLogging.logger("DbCollection-$collectionName")

    suspend fun findOne(id: Id): Data? = collection.find(Filters.eq("_id", id)).firstOrNull()

    suspend fun findAll(): List<Data> = collection.find().toList()

    suspend fun <T> search(field: KProperty1<Data, T>, value: T): List<Data> =
        collection.find(Filters.eq(field.name, value)).toList()

    suspend fun insert(data: Data): Boolean {
        return try {
            collection.insertOne(data).insertedId != null
        } catch (error: MongoException) {
            if (error.code == 11000) {
                logger.warn { "insert duplicate: $collectionName#${data._id}" }
                false
            } else {
                throw error
            }
        }
    }

    /** 全文档 replaceOne + upsert；PlayerActor 的定时/退出存盘使用这一入口。 */
    suspend fun replace(data: Data): Boolean {
        val result = collection.replaceOne(
            Filters.eq("_id", data._id),
            data,
            ReplaceOptions().upsert(true),
        )
        return result.wasAcknowledged() && (result.matchedCount > 0 || result.upsertedId != null)
    }

    /** 以两个旧字段作为乐观锁，成功后返回写入的新文档。 */
    suspend fun <T1, T2> replaceOne(
        field1: KProperty1<Data, T1>,
        old1: T1,
        field2: KProperty1<Data, T2>,
        old2: T2,
        data: Data,
    ): Boolean {
        val filter = Filters.and(
            Filters.eq("_id", data._id),
            Filters.eq(field1.name, old1),
            Filters.eq(field2.name, old2),
        )
        val result = collection.replaceOne(filter, data, ReplaceOptions().upsert(false))
        return result.wasAcknowledged() && result.matchedCount > 0
    }

    suspend fun updateOne(id: Id, filter: Bson, update: Bson, upsert: Boolean = false): Boolean {
        val result = collection.updateOne(
            Filters.and(Filters.eq("_id", id), filter),
            update,
            UpdateOptions().upsert(upsert),
        )
        return result.wasAcknowledged() && (result.matchedCount > 0 || result.upsertedId != null)
    }

    suspend fun <T1, T2> findOneAndUpdate(
        id: Id,
        field1: KProperty1<Data, T1>,
        compare1: T1,
        set1: T1,
        field2: KProperty1<Data, T2>,
        compare2: T2,
        set2: T2,
    ): Data? {
        val filter = Filters.and(
            Filters.eq("_id", id),
            Filters.eq(field1.name, compare1),
            Filters.eq(field2.name, compare2),
        )
        val updates = Updates.combine(
            if (set1 == null) Updates.unset(field1.name) else Updates.set(field1.name, set1),
            if (set2 == null) Updates.unset(field2.name) else Updates.set(field2.name, set2),
        )
        return collection.findOneAndUpdate(
            filter,
            updates,
            FindOneAndUpdateOptions().upsert(false).returnDocument(ReturnDocument.AFTER),
        )
    }
}
