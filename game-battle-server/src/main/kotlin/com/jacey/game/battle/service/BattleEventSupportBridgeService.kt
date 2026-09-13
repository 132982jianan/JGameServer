package com.jacey.game.battle.service

import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.proto3.BaseBattle
import com.jacey.game.db.service.BattleInfoService

/** 桥接 object（避免循环依赖的薄封装） */
object BattleEventSupportBridgeService {
    suspend fun checkWinner(battleId: String, index: Int): Int =
        checkAndGetWinnerUserSeq(battleId, index)

    fun buildPush(eventMsgList: BaseBattle.EventMsgList): NetMessage {
        val pushBuilder = BaseBattle.BattleEventMsgListPush.newBuilder()
            .setEventMsgList(eventMsgList)
        return NetMessage(22001, pushBuilder) // RpcBattleEventMsgListPush
    }

    /** 【游戏核心逻辑】井字棋胜负检测：-1 未完 / 0 平局 / 其它=获胜方行动顺序 */
    suspend fun checkAndGetWinnerUserSeq(battleId: String, justPlacePiecesIndex: Int): Int {
        val allBattleCellInfo = BattleInfoService.getAllBattleCellInfo(battleId)
        val justPlacePiecesUserSeq = allBattleCellInfo[justPlacePiecesIndex]
        val rowNum = justPlacePiecesIndex / 3
        val rowStart = rowNum * 3
        if ((rowStart until rowStart + 3).all { allBattleCellInfo[it] == justPlacePiecesUserSeq }) {
            return justPlacePiecesUserSeq
        }
        val colNum = justPlacePiecesIndex % 3
        if ((colNum..colNum + 6 step 3).all { allBattleCellInfo[it] == justPlacePiecesUserSeq }) {
            return justPlacePiecesUserSeq
        }
        if (justPlacePiecesIndex == 2 || justPlacePiecesIndex == 4 || justPlacePiecesIndex == 6) {
            if ((2..6 step 2).all { allBattleCellInfo[it] == justPlacePiecesUserSeq }) {
                return justPlacePiecesUserSeq
            }
        }
        if (justPlacePiecesIndex == 0 || justPlacePiecesIndex == 4 || justPlacePiecesIndex == 8) {
            if ((0..8 step 4).all { allBattleCellInfo[it] == justPlacePiecesUserSeq }) {
                return justPlacePiecesUserSeq
            }
        }
        return if (allBattleCellInfo.any { it == 0 }) -1 else 0
    }
}