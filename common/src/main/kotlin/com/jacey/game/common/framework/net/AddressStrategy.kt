package com.jacey.game.common.framework.net

import com.jacey.game.common.framework.nacos.IConfig
import kotlinx.serialization.Serializable
import java.net.Inet4Address
import java.net.NetworkInterface

@Serializable
data class AddressStrategy(
    val address: String? = null,
    val envVar: String? = null,
    val prefix: List<String> = listOf("192.", "172.", "10."),
) : IConfig {
    fun resolve(): String {
        address?.let { return it }
        envVar?.let { v -> System.getenv(v)?.let { return it } }
        for (p in prefix) {
            val ip = localIpByPrefix(p)
            if (ip != null) return ip
        }
        return "127.0.0.1"
    }

    private fun localIpByPrefix(prefix: String): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .firstOrNull {
                    !it.isLoopbackAddress && it is Inet4Address && it.hostAddress.startsWith(
                        prefix
                    )
                }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }
}