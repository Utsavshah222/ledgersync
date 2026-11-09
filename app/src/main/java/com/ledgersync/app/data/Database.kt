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
}

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
