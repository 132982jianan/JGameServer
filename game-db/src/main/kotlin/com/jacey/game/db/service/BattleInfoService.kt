package com.jacey.game.db.service

import com.jacey.game.common.framework.redis.Redis
import com.jacey.game.common.proto3.BaseBattle
import com.jacey.game.common.proto3.CommonEnum
import com.jacey.game.db.redis.RKeys
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 对战信息 Redis 操作（object 单例，原 BattleInfoService/Impl + DAO 合一）
 * 全部方法 suspend，协程化 Lettuce 命令。
 */
object BattleInfoService {
    private val logger = KotlinLogging.logger {}
    private val c get() = Redis.commands
    private val k get() = Redis::key

    // ---------- 进行中的对战 ----------

    suspend fun addPlayingBattleId(battleId: String, battleType: CommonEnum.BattleTypeEnum) {
        c.sadd(k("battlePlayingBattleIds:${battleType.number}"), battleId)
    }

    suspend fun removePlayingBattleId(battleId: String, battleType: CommonEnum.BattleTypeEnum) {
        c.srem(k("battlePlayingBattleIds:${battleType.number}"), battleId)
    }

    // ---------- 对战玩家 ----------

    suspend fun initOneBattleUserIds(battleId: String, userIds: List<Int>) {
        c.rpush(k("battleUserIds:$battleId"), *userIds.map { it.toString() }.toTypedArray())
    }

    suspend fun getOneBattleUserIds(battleId: String): List<Int> {
        val list = c.lrange(k("battleUserIds:$battleId"), 0, -1) ?: emptyList()
        return list.map { it.toIntOrNull() ?: 0 }
    }

    suspend fun cleanOneBattleUserIds(battleId: String) {
        c.del(k("battleUserIds:$battleId"))
    }

    suspend fun getOneUserAllOpponentUserIds(battleId: String, userId: Int): List<Int> {
        val userIds = getOneBattleUserIds(battleId).toMutableList()
        userIds.remove(userId)
        return userIds
    }

    suspend fun getOneUserOneOpponentUserId(battleId: String, userId: Int): Int =
        getOneUserAllOpponentUserIds(battleId, userId).first()

    /** 某玩家在某场战斗的行动顺序（先手为1，依次递增） */
    suspend fun getOneUserSeq(battleId: String, userId: Int): Int =
        getOneBattleUserIds(battleId).indexOf(userId) + 1

    suspend fun getOneUserIdBySeq(battleId: String, seq: Int): Int =
        getOneBattleUserIds(battleId)[seq - 1]

    // ---------- 当前回合信息（protobuf 序列化为 Base64 存储） ----------

    suspend fun setBattleCurrentTurnInfo(battleId: String, info: BaseBattle.CurrentTurnInfo) {
        c.hset(k(RKeys.BATTLE_CURRENT_TURN_INFO), Redis.key(battleId),
            java.util.Base64.getEncoder().encodeToString(info.toByteArray()))
    }

    suspend fun getBattleCurrentTurnInfo(battleId: String): BaseBattle.CurrentTurnInfo? {
        val s = c.hget(k(RKeys.BATTLE_CURRENT_TURN_INFO), Redis.key(battleId)) ?: return null
        return BaseBattle.CurrentTurnInfo.parseFrom(java.util.Base64.getDecoder().decode(s))
    }

    suspend fun removeBattleCurrentTurnInfo(battleId: String) {
        c.hdel(k(RKeys.BATTLE_CURRENT_TURN_INFO), Redis.key(battleId))
    }

    // ---------- 棋盘信息 ----------

    suspend fun setOneBattleCellInfo(battleId: String, index: Long, value: Int) {
        c.lset(k("battleCellInfo:$battleId"), index, value.toString())
    }

    suspend fun initAllBattleCellInfo(battleId: String, allCellInfo: List<Int>) {
        c.rpush(k("battleCellInfo:$battleId"), *allCellInfo.map { it.toString() }.toTypedArray())
    }

    suspend fun getOneBattleCellInfo(battleId: String, index: Long): Int? =
        c.lindex(k("battleCellInfo:$battleId"), index)?.toIntOrNull()

    suspend fun getAllBattleCellInfo(battleId: String): List<Int> {
        val list = c.lrange(k("battleCellInfo:$battleId"), 0, -1) ?: emptyList()
        return list.map { it.toIntOrNull() ?: 0 }
    }

    suspend fun cleanAllBattleCellInfo(battleId: String) {
        c.del(k("battleCellInfo:$battleId"))
    }

    // ---------- 事件序列 ----------

    suspend fun addOneBattleEvent(battleId: String, eventMsg: BaseBattle.EventMsg) {
        c.rpush(k("battleEventList:$battleId"),
            java.util.Base64.getEncoder().encodeToString(eventMsg.toByteArray()))
    }

    /** 增加1并返回。如果key不存在，则创建并设置为1然后返回 */
    suspend fun addAndGetNextAvailableEventNum(battleId: String): Int =
        (c.hincrby(k(RKeys.BATTLE_LAST_EVENT_NUM), Redis.key(battleId), 1L) ?: 0L).toInt()

    suspend fun getLastEventNum(battleId: String): Int =
        c.hget(k(RKeys.BATTLE_LAST_EVENT_NUM), Redis.key(battleId))?.toIntOrNull() ?: 0

    suspend fun removeLastEventNum(battleId: String) {
        c.hdel(k(RKeys.BATTLE_LAST_EVENT_NUM), Redis.key(battleId))
    }

    // ---------- 时间与准备状态 ----------

    suspend fun setOneBattleStartTimestamp(battleId: String, startTimestamp: Long) {
        c.hset(k(RKeys.BATTLE_START_TIMESTAMP), Redis.key(battleId), startTimestamp.toString())
    }

    suspend fun getOneBattleStartTimestamp(battleId: String): Long =
        c.hget(k(RKeys.BATTLE_START_TIMESTAMP), Redis.key(battleId))?.toLongOrNull() ?: 0L

    suspend fun removeOneBattleStartTimestamp(battleId: String) {
        c.hdel(k(RKeys.BATTLE_START_TIMESTAMP), Redis.key(battleId))
    }

    suspend fun initOneBattleNotReadyUserIds(battleId: String, userIds: List<Int>) {
        c.sadd(k("battleNotReadyUserIds:$battleId"), *userIds.map { it.toString() }.toTypedArray())
    }

    suspend fun getOneBattleNotReadyUserIds(battleId: String): Set<Int> {
        val members = mutableListOf<Int>()
        c.smembers(k("battleNotReadyUserIds:$battleId")).collect { members.add(it.toIntOrNull() ?: 0) }
        return members.toSet()
    }

    suspend fun removeOneBattleNotReadyUserId(battleId: String, userId: Int) {
        c.srem(k("battleNotReadyUserIds:$battleId"), userId.toString())
    }

    suspend fun cleanOneBattleNotReadyUserIds(battleId: String) {
        c.del(k("battleNotReadyUserIds:$battleId"))
    }

    /** 对战开始时间列表（每日战报用） */
    suspend fun addOneBattleRecord(battleType: CommonEnum.BattleTypeEnum, oneDayZeroClockTimestamp: Long,
                                   battleRecordData: BaseBattle.BattleRecordData) {
        c.rpush(k("battleRecordList:${battleType.number}:$oneDayZeroClockTimestamp"),
            java.util.Base64.getEncoder().encodeToString(battleRecordData.toByteArray()))
    }

    /** sessionId -> logicServerId 绑定（原 LogicServerLoadBalance 里的会话路由部分，保留在 Redis） */
    suspend fun setOneSessionIdToLogicServerId(sessionId: Int, logicServerId: Int) {
        c.hset(k(RKeys.SESSION_ID_TO_LOGIC_SERVER_ID), Redis.key(sessionId.toString()), logicServerId.toString())
    }

    suspend fun getOneSessionIdToLogicServerId(sessionId: Int): Int? =
        c.hget(k(RKeys.SESSION_ID_TO_LOGIC_SERVER_ID), Redis.key(sessionId.toString()))?.toIntOrNull()

    suspend fun removeOneSessionIdToLogicServerId(sessionId: Int) {
        c.hdel(k(RKeys.SESSION_ID_TO_LOGIC_SERVER_ID), Redis.key(sessionId.toString()))
    }

    /** sessionId -> gatewayId 绑定 */
    suspend fun setOneSessionIdToGatewayId(sessionId: Int, gatewayId: Int) {
        c.hset(k(RKeys.SESSION_ID_TO_GATEWAY_ID), Redis.key(sessionId.toString()), gatewayId.toString())
    }

    suspend fun getOneSessionIdToGatewayId(sessionId: Int): Int? =
        c.hget(k(RKeys.SESSION_ID_TO_GATEWAY_ID), Redis.key(sessionId.toString()))?.toIntOrNull()

    suspend fun removeOneSessionIdToGatewayId(sessionId: Int) {
        c.hdel(k(RKeys.SESSION_ID_TO_GATEWAY_ID), Redis.key(sessionId.toString()))
    }

    /** userId -> battleId 绑定（对战中的玩家） */
    suspend fun setBattleUserIdToBattleId(userId: Int, battleId: String) {
        c.hset(k(RKeys.BATTLE_USER_ID_TO_BATTLE_ID), Redis.key(userId.toString()), battleId)
    }

    suspend fun getBattleUserIdToBattleId(userId: Int): String? =
        c.hget(k(RKeys.BATTLE_USER_ID_TO_BATTLE_ID), Redis.key(userId.toString()))

    suspend fun removeBattleUserIdToBattleId(userId: Int) {
        c.hdel(k(RKeys.BATTLE_USER_ID_TO_BATTLE_ID), Redis.key(userId.toString()))
    }

    /** battleId -> battleServerId 绑定 */
    suspend fun setOneBattleIdToBattleServerId(battleId: String, battleServerId: Int) {
        c.hset(k(RKeys.BATTLE_ID_TO_BATTLE_SERVER_ID), Redis.key(battleId), battleServerId.toString())
    }

    suspend fun getOneBattleIdToBattleServerId(battleId: String): Int? =
        c.hget(k(RKeys.BATTLE_ID_TO_BATTLE_SERVER_ID), Redis.key(battleId))?.toIntOrNull()

    suspend fun removeOneBattleIdToBattleServerId(battleId: String) {
        c.hdel(k(RKeys.BATTLE_ID_TO_BATTLE_SERVER_ID), Redis.key(battleId))
    }

    /** battleId -> chatServerId 绑定 */
    suspend fun setOneBattleIdToChatServerId(battleId: String, chatServerId: Int) {
        c.hset(k(RKeys.BATTLE_ID_TO_CHAT_SERVER_ID), Redis.key(battleId), chatServerId.toString())
    }


    suspend fun getOneBattleIdToChatServerId(battleId: String): Int? =
        c.hget(k(RKeys.BATTLE_ID_TO_CHAT_SERVER_ID), Redis.key(battleId))?.toIntOrNull()
}
