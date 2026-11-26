package com.evasi.downloader.queue

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class QueueDatabaseTest {
    private fun row(id: String, position: Long, state: QueueState = QueueState.QUEUED) =
        QueueEntry(id, "same-source", "payload", true, position, id, "X", null, state, album = "test")

    private fun history(uri: String, index: Int) =
        HistoryEntry(uri, "album", "photo-$index.jpg", "image/jpeg", 77L, 123L, 4032, 3024, "Instagram",
            "https://www.instagram.com/p/code/", itemIndex = index, description = "caption")

    @Test fun restartKeepsOrderLeasesPartialAlbumAndRetryEligibility() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val name = "queue-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(app, QueueDatabase::class.java, name).build()
        var db = open()
        try {
            with(db.queue()) {
                insert(row("cancelled", 1, QueueState.CANCELLED))
                insert(row("next", 3))
                insert(row("album", 2, QueueState.DOWNLOADING).copy(lease = "worker", savedCount = 1))
                insert(row("paused", 4, QueueState.PAUSED))
                insert(row("failed", 5, QueueState.FAILED))
                checkpoint(Checkpoint("album", 0, "content://media/images/1", 123))
                record(history("content://media/images/1", 0))
            }
            db.close()
            db = open()
            with(db.queue()) {
                assertEquals(listOf("album", "next"), schedulable().map { it.id })
                assertEquals(listOf("failed", "paused", "next", "album"), duplicates("same-source").map { it.id })
                assertEquals("worker", job("album")?.lease)
                assertEquals(1, job("album")?.savedCount)
                assertEquals(listOf("content://media/images/1"), watchOutputs("album").first())
                assertEquals(history("content://media/images/1", 0), history().first().single())
                assertEquals(6L, nextPosition())
            }
        } finally {
            db.close()
            app.deleteDatabase(name)
        }
    }

    @Test fun bothInstalledVersionOneSchemasMigrateWithoutLosingJobsHistoryOrPartialAlbums() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        for (early in listOf(true, false)) {
            val name = "migrate-${UUID.randomUUID()}.db"
            val first = "content://media/images/13"
            val second = "content://media/images/14"
            try {
                val database = QueueDatabase.open(app, name)
                try {
                    val dao = database.queue()
                    dao.insert(row("album", 1, QueueState.PAUSED).copy(savedCount = 2))
                    dao.record(history(first, 0).copy(missing = early))
                    dao.checkpoint(Checkpoint("album", 0, first, 123, available = false))
                    dao.checkpoint(Checkpoint("album", 1, second, 321))
                    dao.flag(QueueFlag("imported_gallery"))
                } finally { database.close() }
                SQLiteDatabase.openDatabase(app.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { old ->
                    if (early) {
                        old.execSQL("ALTER TABLE checkpoints RENAME TO latest")
                        old.execSQL("""CREATE TABLE checkpoints(jobId TEXT NOT NULL, itemIndex INTEGER NOT NULL,
                            uri TEXT NOT NULL, size INTEGER NOT NULL, PRIMARY KEY(jobId,itemIndex))""")
                        old.execSQL("INSERT INTO checkpoints SELECT jobId,itemIndex,uri,size FROM latest")
                        old.execSQL("DROP TABLE latest")
                    }
                    val hash = if (early) "942e8636154d63d9e6e4c28e66373c19" else "1fb1d920f087e0c39fb24cdead85adfa"
                    old.execSQL("UPDATE room_master_table SET identity_hash = ? WHERE id = 42", arrayOf(hash))
                    old.version = 1
                }
                val upgraded = QueueDatabase.open(app, name)
                try {
                    val dao = upgraded.queue()
                    assertEquals(QueueState.PAUSED, dao.job("album")?.state)
                    assertEquals(2, dao.job("album")?.savedCount)
                    assertEquals(history(first, 0).copy(missing = early), dao.file(first))
                    assertEquals(listOf(first, second), dao.outputs("album").map { it.uri })
                    assertEquals(listOf(second), dao.watchOutputs("album").first())
                    assertTrue(dao.flagged("imported_gallery"))
                } finally { upgraded.close() }
            } finally { app.deleteDatabase(name) }
        }
    }

    @Test fun clearingHistoryAndExternalDeletionPreserveCheckpointOrderWithoutSharingMissingFiles() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(app, QueueDatabase::class.java).build()
        try {
            val dao = db.queue()
            val first = "content://media/images/4"
            val second = "content://media/images/2"
            db.withTransaction {
                for ((index, uri) in listOf(1 to second, 0 to first)) {
                    dao.checkpoint(Checkpoint("album", index, uri, 123))
                    dao.record(history(uri, index))
                }
            }
            assertEquals(listOf(first, second), dao.watchOutputs("album").first())
            dao.markAvailable(first, false)
            dao.markMissing(first, true)
            assertTrue(dao.file(first)!!.missing)
            assertEquals(listOf(second), dao.watchOutputs("album").first())
            dao.clearHistory()
            assertTrue(dao.history().first().isEmpty())
            assertEquals(listOf(first, second), dao.outputs("album").map { it.uri })
            assertEquals(setOf(first, second), dao.trackedUris().toSet())
            assertEquals(listOf(second), dao.watchOutputs("album").first())
        } finally { db.close() }
    }
}
