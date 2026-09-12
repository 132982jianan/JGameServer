package com.jacey.game.common.constants

/** 各节点主 actor 与 actor system 命名约定 */
object GlobalConstant {
    const val GATEWAY_ACTOR_NAME = "gatewayActor"
    const val LOGIC_SERVER_ACTOR_NAME = "logicServerActor"
    const val BATTLE_SERVER_ACTOR_NAME = "battleServerActor"
    const val CHAT_SERVER_ACTOR_NAME = "chatServerActor"
    const val GM_ACTOR_NAME = "gmActor"
}

/** GM token cookie 配置 */
object CookieConstant {
    const val TOKEN = "token"
    /** 过期时间(单位:s) */
    const val EXPIRE = 7200
}

/** SystemConfig.xlsx 表的参数 key */
object SystemConfigKey {
    const val USERNAME_MAX_LENGTH = "usernameMaxLength"
    const val PASSWORD_MIN_LENGTH = "passwordMinLength"
    const val PASSWORD_MAX_LENGTH = "passwordMaxLength"
    const val NICKNAME_MAX_LENGTH = "nicknameMaxLength"
    const val READY_TO_START_GAME_SECOND = "readyToStartGameSecond"
    const val TURN_SECOND = "turnSecond"
}
