package com.agentshell.data.repository

import com.agentshell.core.config.AppConfig
import com.agentshell.core.config.BuildConfig
import com.agentshell.data.local.HostDao
import com.agentshell.data.local.PreferencesDataStore
import com.agentshell.data.model.Host
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.Locale
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HostRepository @Inject constructor(
    private val hostDao: HostDao,
    private val dataStore: PreferencesDataStore,
) {
    private val defaultsMutex = Mutex()

    /** Import newly bundled endpoints on upgrades as well as fresh installs. */
    suspend fun ensureDefaultHosts() = importDefaultHosts(loadBuildTimeHosts())

    internal suspend fun importDefaultHosts(defaults: List<Host>) = defaultsMutex.withLock {
        val existing = hostDao.getAll().first()
        val imported = dataStore.importedDefaultHosts.first()
        val endpoints = existing.mapTo(mutableSetOf()) { it.defaultEndpointKey() }
        var nextOrder = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1
        val added = mutableListOf<Host>()
        for (host in defaults.distinctBy { it.defaultEndpointKey() }) {
            if (host.defaultEndpointKey() in imported || !endpoints.add(host.defaultEndpointKey())) continue
            // Move an unchanged bundled endpoint (e.g. LAN HTTP -> LAN HTTPS) without losing its ID.
            val previous = existing.filter { it.name == host.name && it.defaultEndpointKey() in imported }.singleOrNull()
            if (previous != null) hostDao.insert(previous.copy(address=host.address,port=host.port,tls=host.tls))
            else added.add(host.copy(sortOrder = nextOrder++))
        }
        added.forEach { hostDao.insert(it) }
        // Record existing matches too. A default the user later edits or deletes stays edited/deleted.
        dataStore.markDefaultHostsImported(defaults.mapTo(mutableSetOf()) { it.defaultEndpointKey() })
        if (existing.isEmpty()) {
            added.firstOrNull()?.let { selectHost(it.id) }
        }
    }

    /** Emits a combined flow of (hosts list, selected host). */
    fun getHosts(): Flow<List<Host>> = hostDao.getAll()

    fun getSelectedHost(): Flow<Host?> = combine(
        hostDao.getAll(),
        dataStore.selectedHostId,
    ) { hosts, selectedId ->
        if (selectedId != null) {
            hosts.firstOrNull { it.id == selectedId } ?: hosts.firstOrNull()
        } else {
            hosts.firstOrNull()
        }
    }

    /** One-shot read of the currently selected host (not a Flow). */
    suspend fun getSelectedHostOnce(): Host? = getSelectedHost().first()

    suspend fun saveHost(host: Host) {
        hostDao.insert(host)
    }

    suspend fun deleteHost(id: String) {
        hostDao.deleteById(id)
        // If the deleted host was selected, clear the selection
        val currentSelected = dataStore.selectedHostId.first()
        if (currentSelected == id) {
            val remaining = hostDao.getAll().first()
            dataStore.setSelectedHostId(remaining.firstOrNull()?.id)
        }
    }

    suspend fun selectHost(id: String) {
        if (hostDao.getById(id) == null) return
        dataStore.setSelectedHostId(id)
        updateLastConnected(id)
    }

    suspend fun updateLastConnected(id: String) {
        val host = hostDao.getById(id) ?: return
        hostDao.insert(host.copy(lastConnected = System.currentTimeMillis()))
    }

    /**
     * Parse build-time server list and return as [Host] objects.
     * Format: "host:port,Label|host:port,Label"
     */
    fun loadBuildTimeHosts(): List<Host> = parseBuildTimeHosts(BuildConfig.DEFAULT_SERVER_LIST)

    internal fun parseBuildTimeHosts(raw:String):List<Host> {
        return AppConfig.parseServerList(raw).mapIndexedNotNull { index, (addressPort, label) ->
            if ("://" in addressPort) {
                val uri=runCatching { URI(addressPort) }.getOrNull() ?: return@mapIndexedNotNull null
                if(uri.scheme !in listOf("https","http") || uri.host.isNullOrBlank() || uri.rawUserInfo!=null ||
                    !uri.rawPath.isNullOrEmpty() || uri.rawQuery!=null || uri.rawFragment!=null) return@mapIndexedNotNull null
                val port=if(uri.port==-1) if(uri.scheme=="https") 443 else 80 else uri.port
                if(port !in 1..65535) return@mapIndexedNotNull null
                return@mapIndexedNotNull Host(UUID.randomUUID().toString(),label,uri.host,port,sortOrder=index,tls=uri.scheme=="https")
            }
            val colonIndex = addressPort.lastIndexOf(':')
            if (colonIndex < 0) return@mapIndexedNotNull null
            val address = addressPort.substring(0, colonIndex)
            val port = addressPort.substring(colonIndex + 1).toIntOrNull() ?: return@mapIndexedNotNull null
            if (address.isBlank() || port !in 1..65535) return@mapIndexedNotNull null
            Host(
                id = UUID.randomUUID().toString(),
                name = label,
                address = address,
                port = port,
                sortOrder = index,
            )
        }
    }
}

private fun Host.defaultEndpointKey() = "${address.trim().lowercase(Locale.ROOT)}:$port:${isSecure}"
