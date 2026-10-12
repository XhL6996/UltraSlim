package app.ultraslim.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "replacements")
data class ReplacementEntity(
    @PrimaryKey val historyId: Long,
    val batchId: String,
    val sourceUri: String,
    val outputUri: String,
    val sourceHash: String,
    val outputHash: String,
    val state: String = "PREPARED",
    val note: String = "",
)

@Dao
interface ReplacementDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(item: ReplacementEntity)
    @Update suspend fun update(item: ReplacementEntity)
    @Query("DELETE FROM replacements WHERE historyId = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM replacements WHERE historyId = :id") suspend fun byId(id: Long): ReplacementEntity?
    @Query("SELECT * FROM replacements WHERE state = 'READY' ORDER BY historyId") fun ready(): Flow<List<ReplacementEntity>>
    @Query("UPDATE replacements SET state = :state, note = :note WHERE historyId IN (:ids)")
    suspend fun finish(ids: List<Long>, state: String, note: String)
    @Query("UPDATE replacements SET state = 'CANCELLED', note = '系统确认已中断；可在历史中手动清理原图' WHERE state = 'REQUESTED'")
    suspend fun interruptRequests()
}
