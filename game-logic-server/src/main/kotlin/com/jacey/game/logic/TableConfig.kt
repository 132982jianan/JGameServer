package com.jacey.game.logic

/**
 * 系统参数表（原 SystemConfig.xlsx 固化为代码常量，无需配置文件）
 *
 * 原表内容：
 * usernameMaxLength=18 用户名最大长度
 * passwordMinLength=8  密码最小长度
 * passwordMaxLength=18 密码最大长度
 * nicknameMaxLength=8  昵称最大长度
 */
object TableConfig {
    private val configs = mapOf(
        "usernameMaxLength" to "18",
        "passwordMinLength" to "8",
        "passwordMaxLength" to "18",
        "nicknameMaxLength" to "8",
    )

    fun systemInt(key: String): Int? = configs[key]?.toIntOrNull()
    fun systemString(key: String): String? = configs[key]
}