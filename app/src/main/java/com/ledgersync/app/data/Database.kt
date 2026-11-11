package com.ledgersync.app.data

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
import kotlinx.coroutines.flow.Flow

object SyncState {
    const val PENDING = 0
    const val SYNCED = 1
    const val DUPLICATE = 2 // the sheet already had it (captured by another device)
    const val FAILED = 3
}

@Entity(tableName = "txns", indices = [Index(value = ["txnKey"], unique = true), Index("timestamp")])
data class TxnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Stable fingerprint, identical on every device that receives the same bank SMS. */
    val txnKey: String,
    val timestamp: Long,
    val accountNick: String,
    val bank: String,
    val accountMasked: String,
    val owner: String,
    val type: String, // CREDIT | DEBIT
    val amount: Double,
    val balance: Double?,
    val mode: String,
    val party: String,
    val reference: String,
    val sender: String,
    val body: String,
    val syncState: Int = SyncState.PENDING,
    val syncedAt: Long? = null,
    val attempts: Int = 0,
    val lastError: String? = null,
) {
    val isCredit get() = type == "CREDIT"
}

@Dao
interface TxnDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(t: TxnEntity): Long

    @Query("SELECT * FROM txns ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<TxnEntity>>

    @Query("SELECT * FROM txns WHERE syncState IN (0,3) ORDER BY timestamp ASC LIMIT :limit")
    suspend fun pending(limit: Int): List<TxnEntity>

    @Query("SELECT COUNT(*) FROM txns WHERE syncState IN (0,3)")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM txns WHERE syncState = 3")
    fun observeFailedCount(): Flow<Int>

    @Query("UPDATE txns SET syncState = :state, syncedAt = :at, lastError = NULL WHERE txnKey IN (:keys)")
    suspend fun mark(keys: List<String>, state: Int, at: Long)

    @Query("UPDATE txns SET syncState = 3, attempts = attempts + 1, lastError = :error WHERE txnKey IN (:keys)")
    suspend fun markFailed(keys: List<String>, error: String)

    @Query("DELETE FROM txns")
    suspend fun clear()

    @Query("SELECT * FROM txns ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TxnEntity>>

    /** Entries the sheet's last summary doesn't include yet: still queued, or uploaded after that summary was fetched. */
    @Query("SELECT * FROM txns WHERE syncState IN (0,3) OR (syncState = 1 AND syncedAt > :since) ORDER BY timestamp DESC")
    fun observeUnsettled(since: Long): Flow<List<TxnEntity>>

    /** One growing window of this phone's history; [limit] is pages × 25. */
    @Query("SELECT * FROM txns WHERE $FILTER ORDER BY timestamp DESC LIMIT :limit")
    fun observePage(account: String?, type: String?, unsynced: Boolean, q: String, limit: Int): Flow<List<TxnEntity>>

    @Query(
        "SELECT COUNT(*) AS count, COALESCE(SUM(CASE WHEN type = 'CREDIT' THEN amount END), 0) AS credit, " +
            "COALESCE(SUM(CASE WHEN type = 'DEBIT' THEN amount END), 0) AS debit FROM txns WHERE $FILTER"
    )
    fun observeTotals(account: String?, type: String?, unsynced: Boolean, q: String): Flow<LocalTotals>

    companion object {
        private const val LIKE_Q = "LIKE '%' || :q || '%'"
        const val FILTER = "(:account IS NULL OR accountNick = :account) AND (:type IS NULL OR type = :type) " +
            "AND (:unsynced = 0 OR syncState IN (0,3)) " +
            "AND (:q = '' OR party $LIKE_Q OR accountNick $LIKE_Q OR mode $LIKE_Q OR reference $LIKE_Q OR body $LIKE_Q OR CAST(amount AS TEXT) $LIKE_Q)"
    }
}

data class LocalTotals(val count: Int, val credit: Double, val debit: Double)

@Database(entities = [TxnEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun txns(): TxnDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "ledgersync.db")
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
        }
    }
}
