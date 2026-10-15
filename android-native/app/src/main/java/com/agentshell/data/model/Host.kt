package com.agentshell.data.model

import androidx.room.Entity
import androidx.room.ColumnInfo
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
    val lastConnected: Long? = null,
    val sortOrder: Int = 0,
    @ColumnInfo(defaultValue = "NULL") val tls: Boolean? = null,
) {
    val isSecure: Boolean
        get() = tls ?: (port == 443)

    val wsUrl: String
        get() = if (isSecure) "wss://$address${if (port == 443) "" else ":$port"}" else "ws://$address:$port"

    val httpUrl: String
        get() = if (isSecure) "https://$address${if (port == 443) "" else ":$port"}" else "http://$address:$port"
}
