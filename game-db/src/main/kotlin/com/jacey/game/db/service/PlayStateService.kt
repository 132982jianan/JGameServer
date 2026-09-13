package com.jacey.game.db.service

import com.jacey.game.common.framework.mongo.Mongo
import com.jacey.game.common.framework.mongo.MongoSequence
import com.jacey.game.common.proto3.CommonEnum
import com.jacey.game.common.proto3.CommonMsg
import com.jacey.game.db.entity.PlayStateEntity
import com.mongodb.client.model.Filters
import kotlinx.coroutines.flow.firstOrNull

/**
 * 玩家状态服务（object 单例）
 */
object PlayStateService {
    private val collection get() = Mongo.db.getCollection(PlayStateEntity.Companion.COLLECTION_NAME, PlayStateEntity::class.java)

    suspend fun create(playStateEntity: PlayStateEntity) {
        val entity = if (playStateEntity._id == 0)
            playStateEntity.copy(_id = MongoSequence.nextId(PlayStateEntity.Companion.COLLECTION_NAME))
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