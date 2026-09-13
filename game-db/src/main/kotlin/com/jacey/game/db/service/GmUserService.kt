package com.jacey.game.db.service

import com.jacey.game.common.framework.mongo.Mongo
import com.jacey.game.db.entity.GmUserEntity
import com.jacey.game.db.redis.GmUserRedis
import com.mongodb.client.model.Filters
import kotlinx.coroutines.flow.firstOrNull

/**
 * GM 用户服务（object 单例）
 */
object GmUserService {
    private val collection get() = Mongo.db.getCollection(GmUserEntity.Companion.COLLECTION_NAME, GmUserEntity::class.java)

    suspend fun setGmUserTokenCache(token: String, expire: Int) = GmUserRedis.setGmUserToken(token, expire)

    suspend fun getGmUserTokenCache(token: String): String? = GmUserRedis.getGmUserToken(token)

    suspend fun findGmUserByUsername(username: String): GmUserEntity? =
        collection.find(Filters.eq("username", username)).firstOrNull()

    suspend fun existsAdmin(): Boolean = findGmUserByUsername("admin") != null

    suspend fun saveAdminGmUserEntity() {
        // MD5(admin) 与原 doc/sql/jgame_server.sql 中 admin 账户一致
        collection.insertOne(
            GmUserEntity(_id = 1, username = "admin", passwordMD5 = "21232F297A57A5A743894A0E4A801FC3")
        )
    }
}