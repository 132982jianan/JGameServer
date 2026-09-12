package com.jacey.game.db.service

import com.jacey.game.common.framework.mongo.Mongo
import com.jacey.game.common.framework.mongo.MongoSequence
import com.jacey.game.common.proto3.CommonEnum
import com.jacey.game.common.proto3.CommonMsg
import com.jacey.game.common.util.DateTimeUtil
import com.jacey.game.db.entity.BattleRecordEntity
import com.jacey.game.db.entity.GmUserEntity
import com.jacey.game.db.entity.PlayStateEntity
import com.jacey.game.db.entity.PlayUserEntity
import com.jacey.game.db.redis.GmUserRedis
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.firstOrNull
import org.bson.Document
import java.util.Date

/**
 * 玩家信息服务（object 单例，原 PlayUserService/Impl + DAO 三层合一）
 * 全部方法 suspend：协程驱动（同步写法、非阻塞）
 */
object PlayUserService {
    private val logger = KotlinLogging.logger {}
    private val collection get() = Mongo.db.getCollection(PlayUserEntity.COLLECTION_NAME, PlayUserEntity::class.java)

    suspend fun hasUsername(username: String): Boolean = findByUsername(username) != null

    suspend fun hasUserId(userId: Int): Boolean =
        collection.find(Filters.eq("_id", userId)).firstOrNull() != null

    suspend fun findPlayUserByUsername(username: String): PlayUserEntity? = findByUsername(username)

    private suspend fun findByUsername(username: String): PlayUserEntity? =
        collection.find(Filters.eq("username", username)).firstOrNull()

    suspend fun createNewUser(playUserEntity: PlayUserEntity) {
        // counters 集合原子自增，替代 Mysql AUTO_INCREMENT
        val userId = MongoSequence.nextId(PlayUserEntity.COLLECTION_NAME)
        val entity = playUserEntity.copy(_id = userId)
        collection.insertOne(entity)
        // 初始化 UserState
        val playStateEntity = PlayStateEntity(
            _id = MongoSequence.nextId(PlayStateEntity.COLLECTION_NAME),
            userId = userId,
            userOnlineState = CommonEnum.UserOnlineStateEnum.Offline_VALUE,
            userActionState = CommonEnum.UserActionStateEnum.ActionNone_VALUE,
        )
        PlayStateService.create(playStateEntity)
    }

    suspend fun getUserIdByUsername(username: String): Int? = findByUsername(username)?._id

    suspend fun getUserDataByUserId(userId: Int): CommonMsg.UserData? {
        val user = collection.find(Filters.eq("_id", userId)).firstOrNull() ?: return null
        val builder = CommonMsg.UserData.newBuilder()
            .setUserId(userId)
            .setUsername(user.username)
            .setNickname(user.nickname)
            .setPasswordMD5(user.passwordMD5)
            .setRegistIp(user.registIp)
        user.registTimestamp?.let { builder.setRegistTimestamp(DateTimeUtil.dateToTimestamp(it)) }
        user.lastLoginTimestamp?.let { builder.setLastLoginTimestamp(DateTimeUtil.dateToTimestamp(it)) }
        user.lastLoginIp?.let { builder.setLastLoginIp(it) }
        return builder.build()
    }

    suspend fun getUserBriefInfoByUserId(userId: Int): CommonMsg.UserBriefInfo? {
        val user = collection.find(Filters.eq("_id", userId)).firstOrNull() ?: return null
        val builder = CommonMsg.UserBriefInfo.newBuilder()
            .setUserId(userId)
            .setNickname(user.nickname)
            .setUserState(PlayStateService.getUserStateByUserId(userId))
        return builder.build()
    }

    suspend fun update(userData: CommonMsg.UserData) {
        val user = PlayUserEntity(
            _id = userData.userId,
            username = userData.username,
            nickname = userData.nickname,
            passwordMD5 = userData.passwordMD5,
            registTimestamp = DateFromTimestamp(userData.registTimestamp),  // non-null in proto
            registIp = userData.registIp,
            lastLoginTimestamp = Date.from(DateTimeUtil.timestampToInstant(userData.lastLoginTimestamp)),
            lastLoginIp = userData.lastLoginIp,
        )
        collection.replaceOne(Filters.eq("_id", user._id), user)
    }

    suspend fun getUserInfoByUserId(userId: Int): CommonMsg.UserInfo? {
        val userData = getUserDataByUserId(userId) ?: return null
        return CommonMsg.UserInfo.newBuilder()
            .setUserId(userData.userId)
            .setUsername(userData.username)
            .setNickname(userData.nickname)
            .setUserState(PlayStateService.getUserStateByUserId(userData.userId))
            .build()
    }
}

private fun DateFromTimestamp(timestamp: Long): Date = Date.from(DateTimeUtil.timestampToInstant(timestamp))

/**
 * 玩家状态服务（object 单例）
 */
object PlayStateService {
    private val collection get() = Mongo.db.getCollection(PlayStateEntity.COLLECTION_NAME, PlayStateEntity::class.java)

    suspend fun create(playStateEntity: PlayStateEntity) {
        val entity = if (playStateEntity._id == 0)
            playStateEntity.copy(_id = MongoSequence.nextId(PlayStateEntity.COLLECTION_NAME))
        else playStateEntity
        collection.insertOne(entity)
    }

    suspend fun changeUserOnlineState(userId: Int, isOnline: Boolean) {
        val state = findByUserId(userId) ?: return
        state.userOnlineState =
            if (isOnline) CommonEnum.UserOnlineStateEnum.Online_VALUE
            else CommonEnum.UserOnlineStateEnum.Offline_VALUE
        collection.replaceOne(Filters.eq("_id", state._id), state)
    }

    suspend fun getUserStateByUserId(userId: Int): CommonMsg.UserState {
        val state = findByUserId(userId) ?: return CommonMsg.UserState.getDefaultInstance()
        val builder = CommonMsg.UserState.newBuilder()
            .setOnlineStateValue(state.userOnlineState)
            .setActionStateValue(state.userActionState)
        if (state.userActionState == CommonEnum.UserActionStateEnum.Matching_VALUE) {
            builder.setBattleTypeValue(state.battleType)
        } else if (state.userActionState == CommonEnum.UserActionStateEnum.Playing_VALUE) {
            builder.setBattleTypeValue(state.battleType)
            state.battleId?.let { builder.setBattleId(it) }
        }
        return builder.build()
    }

    suspend fun getPlayStateByUserId(userId: Int): PlayStateEntity? = findByUserId(userId)

    suspend fun changeUserActionState(userId: Int, userActionState: Int, battleType: Int, battleId: String?) {
        val state = findByUserId(userId) ?: return
        state.userActionState = userActionState
        state.battleType = battleType
        state.battleId = battleId
        collection.replaceOne(Filters.eq("_id", state._id), state)
    }

    private suspend fun findByUserId(userId: Int): PlayStateEntity? =
        collection.find(Filters.eq("userId", userId)).firstOrNull()
}

/**
 * GM 用户服务（object 单例）
 */
object GmUserService {
    private val collection get() = Mongo.db.getCollection(GmUserEntity.COLLECTION_NAME, GmUserEntity::class.java)

    suspend fun setGmUserTokenCache(token: String, expire: Int) = GmUserRedis.setGmUserToken(token, expire)

    suspend fun getGmUserTokenCache(token: String): String? = GmUserRedis.getGmUserToken(token)

    suspend fun findGmUserByUsername(username: String): GmUserEntity? =
        collection.find(Filters.eq("username", username)).firstOrNull()

    suspend fun existsAdmin(): Boolean = findGmUserByUsername("admin") != null

    suspend fun saveAdmin() {
        // MD5(admin) 与原 doc/sql/jgame_server.sql 中 admin 账户一致
        collection.insertOne(
            GmUserEntity(_id = 1, username = "admin", passwordMD5 = "21232F297A57A5A743894A0E4A801FC3")
        )
    }
}

/**
 * 对战记录归档服务（object 单例）
 */
object BattleRecordService {
    private val collection get() = Mongo.db.getCollection(BattleRecordEntity.COLLECTION_NAME, BattleRecordEntity::class.java)

    suspend fun saveBattleRecord(battleRecord: BattleRecordEntity) {
        val entity = if (battleRecord._id == 0)
            battleRecord.copy(_id = MongoSequence.nextId(BattleRecordEntity.COLLECTION_NAME))
        else battleRecord
        collection.insertOne(entity)
    }
}
