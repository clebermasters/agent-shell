package com.agentshell.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.agentshell.data.model.SessionTag
import com.agentshell.data.model.SessionTagAssignment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionTagDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: SessionTagDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = database.sessionTagDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun replaceTagsAndAssignments_filtersAssignmentsWithoutParentTags() = runTest {
        val tag = SessionTag(id = "tag-1", name = "Production")

        dao.replaceTagsAndAssignments(
            tags = listOf(tag),
            assignments = listOf(
                SessionTagAssignment(sessionName = "session-a", tagId = "tag-1"),
                SessionTagAssignment(sessionName = "session-b", tagId = "missing-tag"),
            ),
        )

        assertEquals(listOf(tag), dao.getAllTags().first())
        assertEquals(
            listOf(SessionTagAssignment(sessionName = "session-a", tagId = "tag-1")),
            dao.getAllAssignments().first(),
        )
    }

    @Test
    fun replaceTagsAndAssignments_replacesExistingSnapshotAtomically() = runTest {
        dao.replaceTagsAndAssignments(
            tags = listOf(SessionTag(id = "old-tag", name = "Old")),
            assignments = listOf(SessionTagAssignment(sessionName = "session-a", tagId = "old-tag")),
        )

        val newTag = SessionTag(id = "new-tag", name = "New")
        dao.replaceTagsAndAssignments(
            tags = listOf(newTag),
            assignments = listOf(SessionTagAssignment(sessionName = "session-b", tagId = "new-tag")),
        )

        assertEquals(listOf(newTag), dao.getAllTags().first())
        assertEquals(
            listOf(SessionTagAssignment(sessionName = "session-b", tagId = "new-tag")),
            dao.getAllAssignments().first(),
        )
    }
}
