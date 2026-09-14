package com.jacey.game.common.msg

/** 仅在 Lobby 本地 Actor 或节点主 Actor 之间使用，不属于客户端协议号段。 */
object InternalMessageId {
    const val GATE_DISCONNECTED = -901
    const val ACCOUNT_LOGOUT = -1001
    const val PLAYER_LOGIN = -1101
    const val PLAYER_LOGIN_RESULT = -1102
    const val PLAYER_LOGOUT = -1103
    const val PLAYER_FORCE_SAVE = -1104
    const val PLAYER_FORCE_SAVE_RESULT = -1105
    const val LOBBY_GET_OR_CREATE_PLAYER = -1106
    const val LOBBY_FORCE_SAVE_ALL = -1107
    const val GLOBAL_BATTLE_CREATED = -1201
    const val GLOBAL_BATTLE_ENDED = -1202
    const val GLOBAL_CHAT_PUSH = -1203
    const val GLOBAL_PLAYER_NAME_QUERY = -1204
    const val GLOBAL_PLAYER_BATTLE_QUERY = -1205
    const val BATTLE_PLAYER_OFFLINE = -1301
    const val BATTLE_ENDED = -1302
}
