package com.jacey.game.battle.actor

import com.google.protobuf.MessageLite
import com.jacey.game.battle.service.BattleEventSupportBridgeService
import com.jacey.game.battle.service.BattleRoomActorManagerService
import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.common.exception.RpcErrorException
import com.jacey.game.common.framework.akka.ClusterService
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.msg.LocalMessage
import com.jacey.game.common.msg.RemoteMessage
import com.jacey.game.common.proto3.BaseBattle
import com.jacey.game.common.proto3.CommonEnum
import com.jacey.game.common.proto3.RemoteServer
import com.jacey.game.common.proto3.Rpc
import com.jacey.game.common.util.DateTimeUtil
import com.jacey.game.db.entity.BattleRecordEntity
import com.jacey.game.db.service.BattleInfoService
import com.jacey.game.db.service.BattleRecordService
import com.jacey.game.db.service.PlayStateService
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.Date

/**
 * 一场对战一个 Actor（原 BaseBattleActor）：战场初始化 + 事件引擎
 */
class BaseBattleActor : BaseMessageActor() {
    private val log = KotlinLogging.logger {}

    init {
        registerHandler(LocalMessage::class.java) { msg, _ -> onInit(msg) }
    }

    /** 战场初始化（原 initBattle → startFirstTurn） */
    private suspend fun onInit(msg: LocalMessage) {
        val battleRoomInfo = msg.lite as? RemoteServer.BattleRoomInfo ?: return
        val battleId = battleRoomInfo.battleId

        // 参加的人
        val userIds = battleRoomInfo.userIdsList

        // 初始化战场数据
        BattleInfoService.initOneBattleUserIds(battleId, userIds)
        BattleInfoService.initAllBattleCellInfo(battleId, listOf(0, 0, 0, 0, 0, 0, 0, 0, 0))
        BattleInfoService.setOneBattleStartTimestamp(battleId, DateTimeUtil.getCurrentTimestamp())
        BattleInfoService.initOneBattleNotReadyUserIds(battleId, userIds)

        // 通知聊天服务器创建聊天室（askAwait：同步写法，挂起等回复，失败则战场初始化失败）
        val chatRoomInfo = RemoteServer.ChatRoomInfo.newBuilder()
            .setChatRoomType(CommonEnum.ChatRoomTypeEnum.TwoPlayerBattleChatRoomType)
            .setBattleId(battleId)
        val request = RemoteServer.NoticeChatServerCreateNewBattleChatRoomRequest.newBuilder()
            .setChatRoomInfo(chatRoomInfo)

        // 重点例子!!! 请求聊天服Actor
        val reply = ClusterService.askRandomAwait(
            NodeKind.chat,
            RemoteMessage(
                RemoteServer.RemoteRpcNameEnum.RemoteRpcNoticeChatServerCreateNewBattleChatRoom_VALUE,
                request
            )
        )
        if (reply == null ||
            reply.errorCode != RemoteServer.RemoteRpcErrorCodeEnum.RemoteRpcOk_VALUE
        ) {
            log.error { "聊天室创建失败 battleId=$battleId errorCode=${reply?.errorCode}" }
        }
    }

    companion object {
        /** 开始第一回合（原 startFirstTurn） */
        suspend fun startFirstTurn(battleId: String) {
            val userIds = BattleInfoService.getOneBattleUserIds(battleId)
            if (userIds.isEmpty()) return
            val builder = BaseBattle.CurrentTurnInfo.newBuilder()
                .setUserId(userIds[userIds.size - 1]) // 后手先行动（与原实现一致）
                .setTurnCount(0)
            BattleInfoService.setBattleCurrentTurnInfo(battleId, builder.build())
            val startTurnBuilder = BaseBattle.StartTurnEvent.newBuilder()
            val eventMsgBuilder = buildOneEvent(battleId, BaseBattle.EventTypeEnum.EventTypeStartTurn, startTurnBuilder)
            val eventMsgList = doEvent(battleId, eventMsgBuilder)
            pushEventListToAll(battleId, eventMsgList)
        }

        /** 事件引擎（原 doEvent）：todoList 顺序消费 */
        suspend fun doEvent(
            battleId: String,
            firstEvent: BaseBattle.EventMsg.Builder
        ): BaseBattle.EventMsgList.Builder {
            val result = BaseBattle.EventMsgList.newBuilder()
            val todoList = ArrayDeque<BaseBattle.EventMsg.Builder>()
            todoList.add(firstEvent)
            while (todoList.isNotEmpty()) {
                val event = todoList.removeFirst()
                val eventNum = BattleInfoService.addAndGetNextAvailableEventNum(battleId)
                event.eventNum = eventNum
                val nextEvents = when (event.eventTypeValue) {
                    BaseBattle.EventTypeEnum.EventTypeGameOver_VALUE -> doGameOver(battleId, event)
                    BaseBattle.EventTypeEnum.EventTypeStartTurn_VALUE -> doStartTurn(battleId, event)
                    BaseBattle.EventTypeEnum.EventTypeEndTurn_VALUE -> doEndTurn(battleId)
                    BaseBattle.EventTypeEnum.EventTypePlacePieces_VALUE -> doPlacePieces(battleId, event)
                    else -> throw RpcErrorException(Rpc.RpcErrorCodeEnum.ServerError_VALUE)
                }
                result.addMsgList(event.build())
                BattleInfoService.addOneBattleEvent(battleId, event.build())
                nextEvents?.let { todoList.addAll(0, it) }
            }
            return result
        }

        fun buildOneEvent(
            battleId: String,
            eventType: BaseBattle.EventTypeEnum,
            builder: MessageLite.Builder
        ): BaseBattle.EventMsg.Builder {
            val msgBuilder = BaseBattle.EventMsg.newBuilder().setEventType(eventType)
            when (eventType) {
                BaseBattle.EventTypeEnum.EventTypeGameOver ->
                    msgBuilder.setGameOverEvent(builder as BaseBattle.GameOverEvent.Builder)

                BaseBattle.EventTypeEnum.EventTypeStartTurn ->
                    msgBuilder.setStartTurnEvent(builder as BaseBattle.StartTurnEvent.Builder)

                BaseBattle.EventTypeEnum.EventTypeEndTurn ->
                    msgBuilder.setEndTurnEvent(builder as BaseBattle.EndTurnEvent.Builder)

                BaseBattle.EventTypeEnum.EventTypePlacePieces ->
                    msgBuilder.setPlacePiecesEvent(builder as BaseBattle.PlacePiecesEvent.Builder)

                else -> throw RpcErrorException(Rpc.RpcErrorCodeEnum.ServerError_VALUE)
            }
            return msgBuilder
        }

        private suspend fun doStartTurn(
            battleId: String,
            event: BaseBattle.EventMsg.Builder
        ): List<BaseBattle.EventMsg.Builder>? {
            val currentInfo = BattleInfoService.getBattleCurrentTurnInfo(battleId)?.toBuilder() ?: return null
            currentInfo.turnStartTimestamp = DateTimeUtil.getCurrentTimestamp()
            val userIds = BattleInfoService.getOneBattleUserIds(battleId)
            val index = userIds.indexOf(currentInfo.userId)
            val nextIndex = (index + 1) % userIds.size
            currentInfo.userId = userIds[nextIndex]
            if (nextIndex == 0) currentInfo.turnCount = currentInfo.turnCount + 1
            BattleInfoService.setBattleCurrentTurnInfo(battleId, currentInfo.build())
            event.startTurnEvent = event.startTurnEvent.toBuilder().setCurrentTurnInfo(currentInfo).build()
            return null
        }

        private suspend fun doEndTurn(battleId: String): List<BaseBattle.EventMsg.Builder> {
            val startTurnBuilder = BaseBattle.StartTurnEvent.newBuilder()
                .setCurrentTurnInfo(BattleInfoService.getBattleCurrentTurnInfo(battleId))
            val eventMsgBuilder = buildOneEvent(battleId, BaseBattle.EventTypeEnum.EventTypeStartTurn, startTurnBuilder)
            return arrayListOf(eventMsgBuilder)
        }

        private suspend fun doPlacePieces(
            battleId: String,
            event: BaseBattle.EventMsg.Builder
        ): List<BaseBattle.EventMsg.Builder> {
            val nextEvents = arrayListOf<BaseBattle.EventMsg.Builder>()
            val placePieces = event.placePiecesEvent
            val userId = placePieces.userId
            val index = placePieces.index
            val userSeq = BattleInfoService.getOneUserSeq(battleId, userId)
            BattleInfoService.setOneBattleCellInfo(battleId, index.toLong(), userSeq)
            // 胜负检测
            val winnerUserSeq = BattleEventSupportBridgeService.checkWinner(battleId, index)
            if (winnerUserSeq != -1) {
                val gameOverBuilder = BaseBattle.GameOverEvent.newBuilder()
                if (winnerUserSeq == 0) {
                    gameOverBuilder.winnerUserId = 0
                    gameOverBuilder.gameOverReason = BaseBattle.GameOverReasonEnum.GameOverDraw
                } else {
                    gameOverBuilder.winnerUserId = BattleInfoService.getOneUserIdBySeq(battleId, winnerUserSeq)
                    gameOverBuilder.gameOverReason = BaseBattle.GameOverReasonEnum.GameOverPlayerWin
                }
                nextEvents.add(buildOneEvent(battleId, BaseBattle.EventTypeEnum.EventTypeGameOver, gameOverBuilder))
            } else {
                val endTurnBuilder = BaseBattle.EndTurnEvent.newBuilder().setEndTurnUserId(userId)
                nextEvents.add(buildOneEvent(battleId, BaseBattle.EventTypeEnum.EventTypeEndTurn, endTurnBuilder))
            }
            return nextEvents
        }

        private suspend fun doGameOver(
            battleId: String,
            event: BaseBattle.EventMsg.Builder
        ): List<BaseBattle.EventMsg.Builder>? {
            val gameOver = event.gameOverEvent
            val userIds = BattleInfoService.getOneBattleUserIds(battleId)
            val currentTurnInfo = BattleInfoService.getBattleCurrentTurnInfo(battleId)
            // 归档对战记录
            val record = BattleRecordEntity(
                _id = 0,
                battleType = CommonEnum.BattleTypeEnum.BattleTypeTwoPlayer_VALUE,
                battleId = battleId,
                userIdList = userIds.joinToString(","),
                battleStartTimestamp = Date(BattleInfoService.getOneBattleStartTimestamp(battleId)),
                battleEndTimestamp = Date(),
                turnCount = currentTurnInfo?.turnCount ?: 0,
                winnerUserId = gameOver.winnerUserId,
                gameOverReason = gameOver.gameOverReasonValue,
            )
            BattleRecordService.saveBattleRecord(record)
            // 清理玩家对战状态
            for (userId in userIds) {
                PlayStateService.changeUserActionState(
                    userId,
                    CommonEnum.UserActionStateEnum.ActionNone_VALUE,
                    CommonEnum.BattleTypeEnum.NoneType_VALUE,
                    battleId
                )
            }
            BattleInfoService.removePlayingBattleId(battleId, CommonEnum.BattleTypeEnum.BattleTypeTwoPlayer)
            BattleInfoService.cleanOneBattleUserIds(battleId)
            BattleInfoService.removeBattleCurrentTurnInfo(battleId)
            BattleInfoService.cleanAllBattleCellInfo(battleId)
            BattleInfoService.removeLastEventNum(battleId)
            BattleInfoService.removeOneBattleStartTimestamp(battleId)
            BattleInfoService.cleanOneBattleNotReadyUserIds(battleId)
            BattleRoomActorManagerService.removeBattle(battleId)
            return null
        }

        private suspend fun pushEventListToAll(battleId: String, eventMsgList: BaseBattle.EventMsgList.Builder) {
            val netMsg = BattleEventSupportBridgeService.buildPush(eventMsgList.build())
            for (userId in BattleInfoService.getOneBattleUserIds(battleId)) {
                BattleRoomActorManagerService.sendNetMsgToOneUser(userId, netMsg)
            }
        }

        suspend fun pushEventListToOne(userId: Int, eventMsgList: BaseBattle.EventMsgList.Builder) {
            BattleRoomActorManagerService.sendNetMsgToOneUser(
                userId,
                BattleEventSupportBridgeService.buildPush(eventMsgList.build())
            )
        }
    }
}