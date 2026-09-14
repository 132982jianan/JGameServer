package com.jacey.game.db

/** `LoginDebug_<LoginName>` 形式的稳定账号标识。 */
@JvmInline
value class AccountId private constructor(val userId: String) {
    override fun toString(): String = userId

    val loginType: LoginType get() = LoginType.valueOf(userId.substringBefore(SEP))
    val loginName: String get() = userId.substringAfter(SEP)

    companion object {
        const val SEP = "_"

        fun createAccountIdByUserId(userId: String): AccountId = AccountId(userId.trim())

        fun createAccountIdByLoginTypeAndLoginName(loginType: LoginType, loginName: String): AccountId =
            AccountId("${loginType.name}$SEP${loginName.trim()}")
    }
}

enum class LoginType {
    LoginDebug,
}
