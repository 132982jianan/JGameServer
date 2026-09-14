package com.jacey.game.common.msg

/**
 * 客户端上行协议的预留号段，Gate 和 Global 共用。
 * 在号段内新增 RpcNameEnum 协议时，只需实现业务处理器，无需修改中转路由。
 */
object RpcMessageRanges {
    /** 对战请求：获取战局、准备、落子、认输等。 */
    val BATTLE: IntRange = 6000..6999

    /** 聊天请求：加入聊天室、发送消息等。 */
    val CHAT: IntRange = 10001..14000
}
