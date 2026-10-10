package app.ultraslim.data

import androidx.room.Index
import androidx.room.Embedded
import androidx.room.OnConflictStrategy
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.ultraslim.convert.QueueItem
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "history",
    indices = [
        Index(value = ["taskId"], unique = true),
        Index(value = ["createdAt"]),
        Index(value = ["status"]),
    ]
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val inputUri: String,
    /** MediaStore content uri when saved to the album, otherwise a durable private file path. */
    val outputUri: String,
    val inputBytes: Long,
    val outputBytes: Long,
    val width: Int,
    val height: Int,
    val gainmapWidth: Int,
    val gainmapHeight: Int,
    val hadGainMap: Boolean,
    /** Output format label, e.g. "AVIF" / "HEIC". */
    val format: String,
    /** Input container label, e.g. "JPEG" / "HEIC". */
    val inputFormat: String,
    val durationMs: Double,
    val maxContentBoost: Float,
    val createdAt: Long,
    val status: String,
    val taskId: String? = null,
    val batchId: String = "",
    val saveState: String = "SAVED",
    val settingsSummary: String = "",
    val hasExif: Boolean = false,

) {
    companion object {
        const val STATUS_DONE = "DONE"
        const val STATUS_SKIPPED = "SKIPPED"
    }
}

@Dao
interface HistoryDao {

    @Query("SELECT * FROM history WHERE :filter = '' OR saveState = :filter ORDER BY createdAt DESC LIMIT :limit")
    fun flowPage(limit: Int, filter: String): Flow<List<HistoryEntity>>

    /** 与 [flowPage] 同筛选的计数，供"加载更多"页脚比较。 */
    @Query("SELECT COUNT(*) FROM history WHERE :filter = '' OR saveState = :filter")
    fun flowCount(filter: String): Flow<Int>

    @Query("SELECT * FROM history WHERE taskId = :taskId LIMIT 1")
    suspend fun byTask(taskId: String): HistoryEntity?

    @Query("SELECT * FROM history WHERE id = :id")
    suspend fun byId(id: Long): HistoryEntity?

    @Query("SELECT * FROM history")
    suspend fun all(): List<HistoryEntity>

    @Query("SELECT * FROM history WHERE createdAt >= :start AND createdAt < :end ORDER BY createdAt DESC")
    suspend fun inDateRange(start: Long, end: Long): List<HistoryEntity>

    @Query("SELECT * FROM history WHERE id = :id")
    fun flowById(id: Long): Flow<HistoryEntity?>

    @Insert
    suspend fun insert(entity: HistoryEntity): Long

    @Update
    suspend fun update(entity: HistoryEntity)

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM history")
    suspend fun deleteAll()

    @Query("SELECT SUM(inputBytes - outputBytes) FROM history WHERE status = 'DONE'")
    fun totalSaved(): Flow<Long?>

    @Query("SELECT COUNT(*) FROM history WHERE status = 'DONE'")
    fun countDone(): Flow<Int>

    @Query("SELECT COUNT(*) as countDone, COALESCE(SUM(inputBytes), 0) as totalInputBytes, COALESCE(SUM(outputBytes), 0) as totalOutputBytes, COALESCE(SUM(CASE WHEN hadGainMap THEN 1 ELSE 0 END), 0) as gainmapCount FROM history WHERE status = 'DONE'")
    fun flowStats(): Flow<HistoryStats>
}

data class HistoryStats(
    val countDone: Int = 0,
    val totalInputBytes: Long = 0L,
    val totalOutputBytes: Long = 0L,
    val gainmapCount: Int = 0,
) {
    val totalSavedBytes: Long get() = totalInputBytes - totalOutputBytes
    val savedPercent: Float get() = if (totalInputBytes > 0L) (totalSavedBytes.toFloat() / totalInputBytes.toFloat()).coerceIn(-1f, 1f) else 0f
    val avgSavedBytes: Long get() = if (countDone > 0) (totalSavedBytes / countDone) else 0L
    val equivalentPhotosSaved: Int get() = if (countDone > 0 && totalInputBytes > 0L) {
        val avgInput = totalInputBytes / countDone
        if (avgInput > 0) (totalSavedBytes / avgInput).toInt().coerceAtLeast(0) else 0
    } else 0
}

@Entity(tableName = "batches")
data class BatchEntity(@PrimaryKey val id: String, val itemIds: String, val settings: String,
    val cancelled: Boolean = false, val createdAt: Long = System.currentTimeMillis())

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY createdAt") suspend fun all(): List<QueueItem>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(items: List<QueueItem>)
    @Query("DELETE FROM tasks WHERE id IN (:ids)") suspend fun removeIds(ids: List<String>)
    @Transaction suspend fun applyChanges(changed: List<QueueItem>, removed: List<String>) {
        removed.chunked(500).forEach { removeIds(it) }
        if (changed.isNotEmpty()) put(changed)
    }
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putBatch(batch: BatchEntity)
    @Query("SELECT * FROM batches ORDER BY createdAt DESC LIMIT 1") suspend fun latestBatch(): BatchEntity?
    @Query("SELECT * FROM batches WHERE id = :id") suspend fun batch(id: String): BatchEntity?
    @Query("UPDATE batches SET cancelled = 1 WHERE id = :id") suspend fun cancel(id: String)
}

@Database(entities = [HistoryEntity::class, QueueItem::class, BatchEntity::class], version = 4, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun taskDao(): TaskDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE history ADD COLUMN taskId TEXT")
                db.execSQL("ALTER TABLE history ADD COLUMN batchId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE history ADD COLUMN saveState TEXT NOT NULL DEFAULT 'SAVED'")
                db.execSQL("ALTER TABLE history ADD COLUMN settingsSummary TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE history ADD COLUMN hasExif INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE history SET saveState = 'PENDING' WHERE outputUri NOT LIKE 'content://%'")
                db.execSQL("CREATE UNIQUE INDEX index_history_taskId ON history(taskId)")
                db.execSQL(TASK_TABLE_SQL)
                db.execSQL("CREATE TABLE IF NOT EXISTS batches (id TEXT NOT NULL PRIMARY KEY, itemIds TEXT NOT NULL, settings TEXT NOT NULL, cancelled INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_history_createdAt ON history(createdAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_history_status ON history(status)")
            }
        }
        // TranscodeResult 增加输出元数据字段（probe 收敛），tasks 的 result_ 列随之扩展。
        // 注意必须加成可空列：带 Kotlin 默认值的字段，Room 期望 schema 是可空且无 DEFAULT
        // （读取 NULL 时回退到 Kotlin 默认值）；NOT NULL DEFAULT 会导致迁移校验失败。
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN result_hasGainMap INTEGER")
                db.execSQL("ALTER TABLE tasks ADD COLUMN result_hasExif INTEGER")
                db.execSQL("ALTER TABLE tasks ADD COLUMN result_maxContentBoost REAL")
            }
        }
    }
}

private const val TASK_TABLE_SQL = "CREATE TABLE IF NOT EXISTS tasks (id TEXT NOT NULL PRIMARY KEY, uri TEXT NOT NULL, displayName TEXT NOT NULL, sizeBytes INTEGER NOT NULL, status TEXT NOT NULL, progress INTEGER NOT NULL, outputUri TEXT, error TEXT, historyId INTEGER, createdAt INTEGER NOT NULL, batchId TEXT NOT NULL, probe_errorCode INTEGER, probe_error TEXT, probe_hasGainMap INTEGER, probe_container INTEGER, probe_width INTEGER, probe_height INTEGER, probe_gainmapWidth INTEGER, probe_gainmapHeight INTEGER, probe_hasExif INTEGER, probe_maxContentBoost REAL, probe_hdrCapacityMax REAL, result_errorCode INTEGER, result_error TEXT, result_inputHadGainMap INTEGER, result_width INTEGER, result_height INTEGER, result_gainmapWidth INTEGER, result_gainmapHeight INTEGER, result_inputBytes INTEGER, result_outputBytes INTEGER, result_durationMs REAL)"
