package com.agentshell.data.repository

import com.agentshell.core.config.AppConfig
import com.agentshell.core.config.BuildConfig
import com.agentshell.data.local.HostDao
import com.agentshell.data.local.PreferencesDataStore
import com.agentshell.data.model.Host
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HostRepository @Inject constructor(
    private val hostDao: HostDao,
    private val dataStore: PreferencesDataStore,
) {

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
    fun loadBuildTimeHosts(): List<Host> {
        return AppConfig.parseServerList(BuildConfig.DEFAULT_SERVER_LIST).mapIndexedNotNull { index, (addressPort, label) ->
            val colonIndex = addressPort.lastIndexOf(':')
            if (colonIndex < 0) return@mapIndexedNotNull null
            val address = addressPort.substring(0, colonIndex)
            val port = addressPort.substring(colonIndex + 1).toIntOrNull() ?: return@mapIndexedNotNull null
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
