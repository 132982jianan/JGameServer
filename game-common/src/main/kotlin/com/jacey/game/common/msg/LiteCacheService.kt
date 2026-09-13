package com.jacey.game.common.msg

import com.google.protobuf.MessageLite

/** 反射缓存：protobuf Lite 默认实例 */
 object LiteCacheService {
    val cacheClassName2MessageLiteMap = HashMap<String, MessageLite>()
}