package com.jacey.game.db.redis

/**
 * Redis 键封装（原 RedisKeyConstant/Helper 合并）
 *
 * 原有 Redis 数据结构全部保留（sessionId 绑定、负载 zset、akkaPath 等），
 * 保证与旧数据兼容；其中 *LoadBalance 的 akkaPath 注册项已由 Nacos 取代，
 * 仅保留兼容读取（不再写入）。
 */
object RKeys {
    // 客户端自增 sessionId（value，自增）
    const val SESSION_ID_AUTO_INCREASE = "sessionIdAutoIncrease"
    const val SESSION_ID_TO_GATEWAY_ID = "sessionIdToGatewayId"
    const val SESSION_ID_TO_LOGIC_SERVER_ID = "sessionIdToLogicServerId"
    const val USER_ID_TO_SESSION_ID = "userIdToSessionId"
    const val SESSION_ID_TO_USER_ID = "sessionIdToUserId"
    const val BATTLE_USER_ID_TO_BATTLE_ID = "battleUserIdToBattleId"
    const val BATTLE_ID_TO_BATTLE_SERVER_ID = "battleIdToBattleServerId"
    const val BATTLE_ID_TO_CHAT_SERVER_ID = "battleIdToChatServerId"
    const val LOGIC_SERVER_LOAD_BALANCE = "logicServerLoadBalance"
    const val GATEWAY_LOAD_BALANCE = "gatewayLoadBalance"
    const val BATTLE_SERVER_LOAD_BALANCE = "battleServerLoadBalance"
    const val CHAT_SERVER_LOAD_BALANCE = "chatServerLoadBalance"
    const val MAIN_LOGIC_SERVER_ID = "mainLogicServerId"

    // battle 相关
    const val BATTLE_PLAYING_BATTLE_IDS = "battlePlayingBattleIds"
    const val BATTLE_USER_IDS = "battleUserIds"
    const val BATTLE_CURRENT_TURN_INFO = "battleCurrentTurnInfo"
    const val BATTLE_CELL_INFO = "battleCellInfo"
    const val BATTLE_EVENT_LIST = "battleEventList"
    const val BATTLE_LAST_EVENT_NUM = "battleLastEventNum"
    const val BATTLE_START_TIMESTAMP = "battleStartTimestamp"
    const val BATTLE_NOT_READY_USER_IDS = "battleNotReadyUserIds"
    const val BATTLE_RECORD_LIST = "battleRecordList"

    // GM
    const val GM_USER_TOKEN = "gmUserToken"
}

