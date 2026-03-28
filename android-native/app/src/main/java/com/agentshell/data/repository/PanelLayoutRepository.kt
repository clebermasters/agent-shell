package com.agentshell.data.repository

import com.agentshell.data.local.PanelLayoutDao
import com.agentshell.data.model.PanelEntity
import com.agentshell.data.model.PanelLayoutEntity
import com.agentshell.data.model.PanelLayoutWithPanels
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PanelLayoutRepository @Inject constructor(
    private val panelLayoutDao: PanelLayoutDao,
) {
    val allLayouts: Flow<List<PanelLayoutWithPanels>> = panelLayoutDao.getAllWithPanels()

    suspend fun getLayout(id: String): PanelLayoutWithPanels? =
        panelLayoutDao.getByIdWithPanels(id)

    suspend fun saveLayout(layout: PanelLayoutEntity, panels: List<PanelEntity>) {
        panelLayoutDao.insertLayout(layout)
        panelLayoutDao.deletePanelsByLayout(layout.id)
        panelLayoutDao.insertPanels(panels)
    }

    suspend fun deleteLayout(id: String) {
        panelLayoutDao.deleteLayout(id)
    }

    suspend fun markUsed(id: String) {
        panelLayoutDao.updateLastUsed(id, System.currentTimeMillis())
    }
}
