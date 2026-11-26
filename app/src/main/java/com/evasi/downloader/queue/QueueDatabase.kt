package com.evasi.downloader.queue

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

enum class QueueState { QUEUED, DOWNLOADING, PAUSED, FAILED, COMPLETED, CANCELLED }

@Entity(tableName = "queue_jobs", indices = [Index("dedupeKey"), Index("position")])
data class QueueEntry(
    @PrimaryKey val id: String,
    val dedupeKey: String,
    val payload: String,
    val requiresNetwork: Boolean,
    val position: Long,
    val title: String,
    val platform: String,
    val sourceUrl: String?,
    val state: QueueState = QueueState.QUEUED,
    val width: Int? = null,
    val height: Int? = null,
    val album: String,
    val previewUrl: String? = null,
    val description: String? = null,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val speedBytesPerSecond: Long = 0,
    val etaSeconds: Long? = null,
    val itemIndex: Int = 0,
    val itemCount: Int = 0,
    val savedCount: Int = 0,
    val lastUri: String? = null,
    val error: String? = null,
    val lease: String? = null,
    val onlyIndex: Int? = null,
)

@Entity(tableName = "saved_media", indices = [Index("jobId"), Index("date")])
data class HistoryEntry(
    @PrimaryKey val uri: String,
    val jobId: String?,
    val title: String,
    val mime: String,
    val date: Long,
    val size: Long,
    val width: Int?,
    val height: Int?,
    val platform: String,
    val sourceUrl: String?,
    val itemIndex: Int = 0,
    val missing: Boolean = false,
    val description: String? = null,
    val previewUrl: String? = null,
)

/** Checkpoints survive clearing visible history so a partial album never repeats finished items. */
@Entity(tableName = "checkpoints", primaryKeys = ["jobId", "itemIndex"])
internal data class Checkpoint(val jobId: String, val itemIndex: Int, val uri: String, val size: Long, val available: Boolean = true)

@Entity(tableName = "queue_flags")
internal data class QueueFlag(@PrimaryKey val name: String)

@Dao
internal interface QueueDao {
    @Query("SELECT * FROM queue_jobs ORDER BY position") fun jobs(): Flow<List<QueueEntry>>
    @Query("SELECT * FROM queue_jobs WHERE id = :id") fun watchJob(id: String): Flow<QueueEntry?>
    @Query("SELECT id FROM queue_jobs WHERE state NOT IN ('COMPLETED','CANCELLED')") suspend fun neededInputs(): List<String>
    @Query("SELECT * FROM saved_media ORDER BY date DESC, itemIndex, uri") fun history(): Flow<List<HistoryEntry>>
    @Query("SELECT * FROM queue_jobs WHERE id = :id") suspend fun job(id: String): QueueEntry?
    @Query("SELECT * FROM queue_jobs WHERE dedupeKey = :key AND state != 'CANCELLED' ORDER BY position DESC")
    suspend fun duplicates(key: String): List<QueueEntry>
    @Query("SELECT COALESCE(MAX(position), 0) + 1 FROM queue_jobs") suspend fun nextPosition(): Long
    @Query("SELECT * FROM queue_jobs WHERE state IN ('QUEUED','DOWNLOADING') ORDER BY position")
    suspend fun schedulable(): List<QueueEntry>
    @Insert suspend fun insert(job: QueueEntry)
    @Update suspend fun update(job: QueueEntry)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun record(entry: HistoryEntry)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun importRecords(entries: List<HistoryEntry>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun checkpoint(entry: Checkpoint)
    @Query("SELECT * FROM checkpoints WHERE jobId = :id ORDER BY itemIndex") suspend fun outputs(id: String): List<Checkpoint>
    @Query("SELECT uri FROM checkpoints WHERE jobId = :id AND available = 1 ORDER BY itemIndex") fun watchOutputs(id: String): Flow<List<String>>
    @Query("SELECT * FROM saved_media WHERE uri = :uri") suspend fun file(uri: String): HistoryEntry?
    @Query("SELECT uri FROM saved_media UNION SELECT uri FROM checkpoints") suspend fun trackedUris(): List<String>
    @Query("UPDATE saved_media SET missing = :missing WHERE uri = :uri AND missing != :missing") suspend fun markMissing(uri: String, missing: Boolean)
    @Query("UPDATE checkpoints SET available = :available WHERE uri = :uri AND available != :available")
    suspend fun markAvailable(uri: String, available: Boolean)
    @Query("DELETE FROM saved_media WHERE uri = :uri") suspend fun removeFile(uri: String)
    @Query("DELETE FROM saved_media") suspend fun clearHistory()
    @Query("SELECT EXISTS(SELECT 1 FROM queue_flags WHERE name = :name)") suspend fun flagged(name: String): Boolean
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun flag(flag: QueueFlag)
}

@Database(entities = [QueueEntry::class, HistoryEntry::class, Checkpoint::class, QueueFlag::class], version = 2, exportSchema = false)
internal abstract class QueueDatabase : RoomDatabase() {
    abstract fun queue(): QueueDao

    companion object {
        fun open(context: Context, name: String = "downloads.db"): QueueDatabase =
            Room.databaseBuilder(context, QueueDatabase::class.java, name).addMigrations(versionOne).build()

        // Both early version-one layouts were installed on devices. Keep existing availability flags
        // when present, and infer them from visible history for the older four-column checkpoints.
        private val versionOne = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val hasAvailability = db.query("PRAGMA table_info(checkpoints)").use { columns ->
                    val name = columns.getColumnIndexOrThrow("name")
                    generateSequence { if (columns.moveToNext()) columns.getString(name) else null }
                        .any { it == "available" }
                }
                if (hasAvailability) return
                db.execSQL("""CREATE TABLE checkpoints_v2 (
                    jobId TEXT NOT NULL, itemIndex INTEGER NOT NULL, uri TEXT NOT NULL, size INTEGER NOT NULL,
                    available INTEGER NOT NULL, PRIMARY KEY(jobId, itemIndex))""")
                db.execSQL("""INSERT INTO checkpoints_v2 (jobId, itemIndex, uri, size, available)
                    SELECT c.jobId, c.itemIndex, c.uri, c.size, NOT EXISTS (
                        SELECT 1 FROM saved_media h WHERE h.uri = c.uri AND h.missing = 1)
                    FROM checkpoints c""")
                db.execSQL("DROP TABLE checkpoints")
                db.execSQL("ALTER TABLE checkpoints_v2 RENAME TO checkpoints")
            }
        }
    }
}
