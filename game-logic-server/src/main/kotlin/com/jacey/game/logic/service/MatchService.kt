package com.jacey.game.logic.service

import com.jacey.game.common.proto3.CommonEnum
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.proto3.CommonMsg
import com.jacey.game.db.service.PlayStateService
import com.jacey.game.db.service.PlayUserService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 匹配服务（object 单例，原 MatchService/Impl）
 *
 * 匹配队列为进程内状态（主逻辑服单点处理匹配），配 Mutex 串行化，
 * doMatch 由 MatchActor 每秒驱动一次。
 */
object MatchService {
    private val logger = KotlinLogging.logger {}
    private val matchMutex = Mutex()

    @Volatile
    private var isStopMatch = false
    private val matchTwoPlayerQueue = ConcurrentLinkedQueue<Int>()

    suspend fun doMatch() {
        if (!isStopMatch) {
            doSimpleTwoPlayerBattleMatch()
        }
    }

    suspend fun addMatchPlayer(userId: Int, battleType: CommonEnum.BattleTypeEnum): Boolean {
        when (battleType.number) {
            CommonEnum.BattleTypeEnum.BattleTypeTwoPlayer_VALUE -> {
                matchTwoPlayerQueue.add(userId)
            }
            else -> {
                logger.error { "【匹配任务添加失败】not support battleType=$battleType" }
                return false
            }
        }
        PlayStateService.changeUserActionState(
            userId,
            CommonEnum.UserActionStateEnum.Matching_VALUE,
            CommonEnum.BattleTypeEnum.BattleTypeTwoPlayer_VALUE,
            null
        )
        return true
    }

    suspend fun removeMatchPlayer(userId: Int, battleType: CommonEnum.BattleTypeEnum?): Boolean {
        if (battleType == null) return false
        val removed = when (battleType.number) {
            CommonEnum.BattleTypeEnum.BattleTypeTwoPlayer_VALUE -> matchTwoPlayerQueue.remove(userId)
            else -> {
                logger.error { "【匹配任务移除失败】not support battleType=$battleType" }
                return false
            }
        }
        if (removed) {
            PlayStateService.changeUserActionState(
                userId,
                CommonEnum.UserActionStateEnum.ActionNone_VALUE,
                CommonEnum.BattleTypeEnum.NoneType_VALUE,
                null
            )
        }
        return removed
    }

    suspend fun stopMatch() {
        isStopMatch = true
        for (userId in matchTwoPlayerQueue) {
            PlayStateService.changeUserActionState(
                userId,
                CommonEnum.UserActionStateEnum.ActionNone_VALUE,
                CommonEnum.BattleTypeEnum.NoneType_VALUE,
                null
            )
        }
    }

    private suspend fun doSimpleTwoPlayerBattleMatch() = matchMutex.withLock {
        if (matchTwoPlayerQueue.size > 1) {
            val first = matchTwoPlayerQueue.poll()
            val second = matchTwoPlayerQueue.poll()
            if (first == null || second == null) return@withLock
            val matchUserIds = arrayListOf(first, second)
            matchUserIds.shuffle()
            logger.info { "【1v1对战匹配】userId=${matchUserIds[0]} and ${matchUserIds[1]}" }
            doAfterMatchSuccess(CommonEnum.BattleTypeEnum.BattleTypeTwoPlayer, matchUserIds)
        }
    }

    private suspend fun doAfterMatchSuccess(battleType: CommonEnum.BattleTypeEnum, userIds: List<Int>) {
        val battleId = generateBattleId(battleType)
        val isSuccess = MessageRouterService.noticeBattleServerCreateNewBattle(battleType, battleId, userIds)
        if (isSuccess) {
            for (userId in userIds) {
                PlayStateService.changeUserActionState(
                    userId,
                    CommonEnum.UserActionStateEnum.Playing_VALUE,
                    CommonEnum.BattleTypeEnum.BattleTypeTwoPlayer_VALUE,
                    battleId
                )
            }
            sendMatchSuccessPush(battleType, battleId, userIds)
        } else {
            sendMatchFailPush(battleType, userIds)
        }
    }

    /** 匹配成功推送（原 MatchActor.onBattleCreated：battle 响应已被 askAwait 消费，推送在 ask 调用方完成） */
    private suspend fun sendMatchSuccessPush(battleType: CommonEnum.BattleTypeEnum, battleId: String, userIds: List<Int>) {
        val pushBuilder = CommonMsg.MatchResultPush.newBuilder()
            .setIsSuccess(true)
            .setBattleType(battleType)
            .setBattleId(battleId)
        for (userId in userIds) {
            val brief = PlayUserService.getUserBriefInfoByUserId(userId)
            if (brief != null) pushBuilder.addUserBriefInfos(brief)
        }
        val netMsg = NetMessage(21001, pushBuilder) // RpcMatchResultPush
        for (userId in userIds) {
            MessageRouterService.sendNetMsgToOneUser(userId, netMsg)
        }
    }

    private suspend fun sendMatchFailPush(battleType: CommonEnum.BattleTypeEnum, userIds: List<Int>) {
        val pushBuilder = CommonMsg.MatchResultPush.newBuilder()
            .setIsSuccess(false)
            .setBattleType(battleType)
        val netMsg = NetMessage(21001, pushBuilder) // RpcMatchResultPush
        for (userId in userIds) {
            MessageRouterService.sendNetMsgToOneUser(userId, netMsg)
        }
    }

    private fun generateBattleId(battleType: CommonEnum.BattleTypeEnum): String =
        "${battleType.number}_" + UUID.randomUUID().toString().replace("-", "")
}
