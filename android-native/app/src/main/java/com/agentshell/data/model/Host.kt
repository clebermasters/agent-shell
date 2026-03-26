package com.agentshell.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(tableName = "hosts")
@Serializable
data class Host(
    @PrimaryKey
    val id: String,
    val name: String,
    val address: String,
    val port: Int,
    val lastConnected: Long? = null
) {
    val isSecure: Boolean
        get() = port == 443

    val wsUrl: String
        get() = if (isSecure) "wss://$address" else "ws://$address:$port"

    val httpUrl: String
        get() = if (isSecure) "https://$address" else "http://$address:$port"
}
