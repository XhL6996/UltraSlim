package app.ultraslim.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val inputUri: String,
    /** MediaStore content uri when saved to the album, otherwise a cache file path. */
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
) {
    companion object {
        const val STATUS_DONE = "DONE"
        const val STATUS_SKIPPED = "SKIPPED"
    }
}

@Dao
interface HistoryDao {

    @Query("SELECT * FROM history ORDER BY createdAt DESC")
    fun flowAll(): Flow<List<HistoryEntity>>

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
}

@Database(entities = [HistoryEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
}
