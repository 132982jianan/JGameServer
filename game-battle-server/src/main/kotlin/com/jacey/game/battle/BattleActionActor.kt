package com.jacey.game.battle

import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.common.exception.RpcErrorException
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.proto3.BaseBattle
import com.jacey.game.common.proto3.CommonEnum
import com.jacey.game.common.proto3.CommonMsg
import com.jacey.game.common.proto3.Rpc
import com.jacey.game.common.framework.config.AppConfig
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.framework.net.NodeRegister
import com.jacey.game.db.service.BattleInfoService
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * 对战请求处理 Actor（原 4 个 baseBattle Action 合并）
 * - GetBattleInfo：获取战场信息
 * - PlacePieces：落子 + 事件引擎
 * - Concede：认输
 * - ReadyToStartGame：确认开始
 */
class BattleActionActor : BaseMessageActor() {
    private val log = KotlinLogging.logger {}

    init {
        registerHandler(NetMessage::class.java) { msg, sender -> onNetMessage(msg) }
    }

    private suspend fun onNetMessage(msg: NetMessage) {
        when (msg.rpcNum) {
            Rpc.RpcNameEnum.GetBattleInfo_VALUE -> onGetBattleInfo(msg)
            Rpc.RpcNameEnum.PlacePieces_VALUE -> onPlacePieces(msg)
            Rpc.RpcNameEnum.Concede_VALUE -> onConcede(msg)
            Rpc.RpcNameEnum.ReadyToStartGame_VALUE -> onReadyToStartGame(msg)
            else -> throw RpcErrorException(Rpc.RpcErrorCodeEnum.ServerError_VALUE)
        }
    }

    private suspend fun onGetBattleInfo(msg: NetMessage) {
        val userId = msg.userId
        val battleId = BattleInfoService.getBattleUserIdToBattleId(userId)
            ?: throw RpcErrorException(Rpc.RpcErrorCodeEnum.UserNotInBattle_VALUE)
        val battleInfoBuilder = BaseBattle.BattleInfo.newBuilder()
        for (oneUserId in BattleInfoService.getOneBattleUserIds(battleId)) {
            val brief = com.jacey.game.db.service.PlayUserService.getUserBriefInfoByUserId(oneUserId)
            if (brief != null) battleInfoBuilder.addUserBriefInfos(brief)
        }
        battleInfoBuilder.battleStartTimestamp = BattleInfoService.getOneBattleStartTimestamp(battleId)
        battleInfoBuilder.addAllBattleCellInfo(BattleInfoService.getAllBattleCellInfo(battleId))
        battleInfoBuilder.lastEventNum = BattleInfoService.getLastEventNum(battleId)
        val notReadyUserIds = BattleInfoService.getOneBattleNotReadyUserIds(battleId)
        if (notReadyUserIds.isEmpty()) {
            BattleInfoService.getBattleCurrentTurnInfo(battleId)?.let {
                battleInfoBuilder.currentTurnInfo = it
            }
        } else {
            battleInfoBuilder.addAllNotReadyUserIds(notReadyUserIds)
        }
        val builder = BaseBattle.GetBattleInfoResponse.newBuilder()
            .setBattleInfo(battleInfoBuilder)
        sender()?.tell(buildResponse(userId, Rpc.RpcNameEnum.GetBattleInfo_VALUE, builder), null)
    }

    private suspend fun onPlacePieces(msg: NetMessage) {
        val userId = msg.userId
        val req = msg.getProto<BaseBattle.PlacePiecesRequest>()
            ?: throw RpcErrorException(Rpc.RpcErrorCodeEnum.ServerError_VALUE)
        val inputLastEventNum = req.lastEventNum
        val inputIndex = req.index
        val battleId = BattleInfoService.getBattleUserIdToBattleId(userId)
            ?: throw RpcErrorException(Rpc.RpcErrorCodeEnum.UserNotInBattle_VALUE)
        val notReady = BattleInfoService.getOneBattleNotReadyUserIds(battleId)
        if (notReady.isNotEmpty()) throw RpcErrorException(Rpc.RpcErrorCodeEnum.BattleNotStart_VALUE)
        val currentTurn = BattleInfoService.getBattleCurrentTurnInfo(battleId)
        if (currentTurn == null || currentTurn.userId != userId) {
            throw RpcErrorException(Rpc.RpcErrorCodeEnum.IsNotUserTurn_VALUE)
        }
        val lastEventNum = BattleInfoService.getLastEventNum(battleId)
        if (lastEventNum != inputLastEventNum) {
            throw RpcErrorException(Rpc.RpcErrorCodeEnum.InputLastEventNumError_VALUE)
        }
        if (inputIndex < 0 || inputIndex > 8) {
            throw RpcErrorException(Rpc.RpcErrorCodeEnum.PlacePiecesErrorIndexError_VALUE)
        }
        val cell = BattleInfoService.getOneBattleCellInfo(battleId, inputIndex.toLong())
        if (cell != null && cell != 0) {
            throw RpcErrorException(Rpc.RpcErrorCodeEnum.PlacePiecesErrorIndexIsNotEmpty_VALUE)
        }
        // 在事件清理前取得对手 id
        val opponentUserId = BattleInfoService.getOneUserOneOpponentUserId(battleId, userId)
        val piecesBuilder = BaseBattle.PlacePiecesEvent.newBuilder()
            .setUserId(userId)
            .setIndex(inputIndex)
        val eventBuilder = BaseBattleActor.buildOneEvent(
            battleId, BaseBattle.EventTypeEnum.EventTypePlacePieces, piecesBuilder)
        val eventMsgList = BaseBattleActor.doEvent(battleId, eventBuilder)
        // 推送给对手
        BaseBattleActor.pushEventListToOne(opponentUserId, eventMsgList)
        val builder = BaseBattle.PlacePiecesResponse.newBuilder()
            .setEventList(eventMsgList)
        sender()?.tell(buildResponse(userId, Rpc.RpcNameEnum.PlacePieces_VALUE, builder), null)
    }

    private suspend fun onConcede(msg: NetMessage) {
        val userId = msg.userId
        val battleId = BattleInfoService.getBattleUserIdToBattleId(userId)
            ?: throw RpcErrorException(Rpc.RpcErrorCodeEnum.UserNotInBattle_VALUE)
        val notReady = BattleInfoService.getOneBattleNotReadyUserIds(battleId)
        if (notReady.isNotEmpty()) throw RpcErrorException(Rpc.RpcErrorCodeEnum.BattleNotStart_VALUE)
        val opponentUserId = BattleInfoService.getOneUserOneOpponentUserId(battleId, userId)
        val gameOverBuilder = BaseBattle.GameOverEvent.newBuilder()
            .setGameOverReason(BaseBattle.GameOverReasonEnum.GameOverPlayerConcede)
            .setWinnerUserId(BattleInfoService.getOneUserOneOpponentUserId(battleId, userId))
        val eventBuilder = BaseBattleActor.buildOneEvent(
            battleId, BaseBattle.EventTypeEnum.EventTypeGameOver, gameOverBuilder)
        val eventMsgList = BaseBattleActor.doEvent(battleId, eventBuilder)
        BaseBattleActor.pushEventListToOne(opponentUserId, eventMsgList)
        val builder = BaseBattle.ConcedeResponse.newBuilder()
            .setEventList(eventMsgList)
        sender()?.tell(buildResponse(userId, Rpc.RpcNameEnum.Concede_VALUE, builder), null)
    }

    private suspend fun onReadyToStartGame(msg: NetMessage) {
        val userId = msg.userId
        val battleId = BattleInfoService.getBattleUserIdToBattleId(userId)
            ?: throw RpcErrorException(Rpc.RpcErrorCodeEnum.UserNotInBattle_VALUE)
        val notReadyUserIds = BattleInfoService.getOneBattleNotReadyUserIds(battleId)
        if (!notReadyUserIds.contains(userId)) {
            throw RpcErrorException(Rpc.RpcErrorCodeEnum.ReadyToStartGameErrorAlreadyReady_VALUE)
        }
        BattleInfoService.removeOneBattleNotReadyUserId(battleId, userId)
        val remain = BattleInfoService.getOneBattleNotReadyUserIds(battleId)
        if (remain.isEmpty()) {
            BaseBattleActor.startFirstTurn(battleId)
        }
        val builder = BaseBattle.ReadyToStartGameResponse.newBuilder()
        sender()?.tell(buildResponse(userId, Rpc.RpcNameEnum.ReadyToStartGame_VALUE, builder), null)
    }

    private fun buildResponse(
        userId: Int,
        rpcNum: Int,
        builder: com.google.protobuf.MessageLite.Builder
    ): NetMessage {
        val message = NetMessage(rpcNum, builder)
        message.userId = userId
        return message
    }
}
