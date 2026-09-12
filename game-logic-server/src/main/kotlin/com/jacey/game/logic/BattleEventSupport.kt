package com.jacey.game.logic

import com.jacey.game.common.proto3.BaseBattle
import com.jacey.game.common.proto3.Rpc
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.db.service.BattleInfoService

/**
 * 对战事件辅助（object 单例）
 *
 * 供 battle 模块复用的井字棋胜负判定与事件推送构造。
 */
object BattleEventSupport {
    /** 【游戏核心逻辑】井字棋胜负检测：-1 未完 / 0 平局 / 其它=获胜方行动顺序 */
    suspend fun checkAndGetWinnerUserSeq(battleId: String, justPlacePiecesIndex: Int): Int {
        val allBattleCellInfo = BattleInfoService.getAllBattleCellInfo(battleId)
        val justPlacePiecesUserSeq = allBattleCellInfo[justPlacePiecesIndex]

        // 横行
        val rowNum = justPlacePiecesIndex / 3
        val rowStart = rowNum * 3
        if ((rowStart until rowStart + 3).all { allBattleCellInfo[it] == justPlacePiecesUserSeq }) {
            return justPlacePiecesUserSeq
        }
        // 竖列
        val colNum = justPlacePiecesIndex % 3
        if ((colNum..colNum + 6 step 3).all { allBattleCellInfo[it] == justPlacePiecesUserSeq }) {
            return justPlacePiecesUserSeq
        }
        // 斜线（/）
        if (justPlacePiecesIndex == 2 || justPlacePiecesIndex == 4 || justPlacePiecesIndex == 6) {
            if ((2..6 step 2).all { allBattleCellInfo[it] == justPlacePiecesUserSeq }) {
                return justPlacePiecesUserSeq
            }
        }
        // 反斜线（\）
        if (justPlacePiecesIndex == 0 || justPlacePiecesIndex == 4 || justPlacePiecesIndex == 8) {
            if ((0..8 step 4).all { allBattleCellInfo[it] == justPlacePiecesUserSeq }) {
                return justPlacePiecesUserSeq
            }
        }
        // 无胜者：棋盘满则平局
        return if (allBattleCellInfo.any { it == 0 }) -1 else 0
    }

    /** 战场事件列表推送消息构造 */
    fun buildEventListPush(eventMsgList: BaseBattle.EventMsgList): NetMessage {
        val pushBuilder = BaseBattle.BattleEventMsgListPush.newBuilder()
            .setEventMsgList(eventMsgList)
        return NetMessage(22001, pushBuilder) // RpcBattleEventMsgListPush
    }
}
