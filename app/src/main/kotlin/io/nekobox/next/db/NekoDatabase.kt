package io.nekobox.next.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val sortOrder: Int,
    val selectedNodeId: String?,
)

@Entity(
    tableName = "nodes",
    indices = [Index("groupId"), Index("subscriptionId")],
)
data class NodeEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    val name: String,
    val protocol: String,
    val schemaVersion: Int,
    val configJson: String,
    val subscriptionId: String?,
    val latencyMs: Int?,
    val sortOrder: Int,
    val pinned: Int,
    val updatedAt: Long,
)

@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    val groupId: String,
    val userAgent: String,
    val autoUpdateHours: Int,
    val lastUpdatedAt: Long?,
    val lastError: String?,
)

@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey val id: String,
    val enabled: Int,
    val schemaVersion: Int,
    val configJson: String,
    val sortOrder: Int,
)

@Entity(tableName = "rule_sets")
data class RuleSetEntity(
    @PrimaryKey val tag: String,
    val url: String,
    val format: String,
)

@Entity(tableName = "app_packages")
data class AppPackageEntity(
    @PrimaryKey val packageName: String,
)

@Dao
interface GroupDao {
    @Query("SELECT * FROM groups ORDER BY sortOrder, name")
    fun observe(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups ORDER BY sortOrder, name")
    suspend fun all(): List<GroupEntity>

    @Query("SELECT COUNT(*) FROM groups")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: GroupEntity)

    @Query("DELETE FROM groups WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE groups SET selectedNodeId = :nodeId WHERE id = :id")
    suspend fun select(id: String, nodeId: String?)
}

@Dao
interface NodeDao {
    @Query("SELECT * FROM nodes WHERE groupId = :groupId ORDER BY pinned DESC, sortOrder, name")
    fun observe(groupId: String): Flow<List<NodeEntity>>

    @Query("SELECT * FROM nodes WHERE groupId = :groupId ORDER BY pinned DESC, sortOrder, name")
    suspend fun inGroup(groupId: String): List<NodeEntity>

    @Query("SELECT * FROM nodes")
    suspend fun all(): List<NodeEntity>

    @Query("SELECT * FROM nodes WHERE id = :id")
    suspend fun get(id: String): NodeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: NodeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<NodeEntity>)

    @Query("DELETE FROM nodes WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM nodes WHERE subscriptionId = :subscriptionId")
    suspend fun deleteSubscription(subscriptionId: String)

    @Query("UPDATE nodes SET latencyMs = :latency WHERE id = :id")
    suspend fun latency(id: String, latency: Int?)

    @Query("UPDATE nodes SET sortOrder = :order WHERE id = :id")
    suspend fun order(id: String, order: Int)
}

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY name")
    fun observe(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions")
    suspend fun all(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscriptions WHERE id = :id")
    suspend fun get(id: String): SubscriptionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SubscriptionEntity)

    @Query("DELETE FROM subscriptions WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules ORDER BY sortOrder")
    fun observe(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM rules ORDER BY sortOrder")
    suspend fun all(): List<RuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RuleEntity)

    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface RuleSetDao {
    @Query("SELECT * FROM rule_sets")
    fun observe(): Flow<List<RuleSetEntity>>

    @Query("SELECT * FROM rule_sets")
    suspend fun all(): List<RuleSetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RuleSetEntity)

    @Query("DELETE FROM rule_sets WHERE tag = :tag")
    suspend fun delete(tag: String)
}

@Dao
interface AppPackageDao {
    @Query("SELECT packageName FROM app_packages")
    fun observe(): Flow<List<String>>

    @Query("SELECT packageName FROM app_packages")
    suspend fun all(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AppPackageEntity)

    @Query("DELETE FROM app_packages WHERE packageName = :name")
    suspend fun delete(name: String)
}

/**
 * Schema v1 is the original table set. v2 adds nodes.pinned without
 * dropping data. Destructive fallback is intentionally absent.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE nodes ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
    }
}

@Database(
    entities = [
        GroupEntity::class,
        NodeEntity::class,
        SubscriptionEntity::class,
        RuleEntity::class,
        RuleSetEntity::class,
        AppPackageEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class NekoDatabase : RoomDatabase() {
    abstract fun groups(): GroupDao
    abstract fun nodes(): NodeDao
    abstract fun subscriptions(): SubscriptionDao
    abstract fun rules(): RuleDao
    abstract fun ruleSets(): RuleSetDao
    abstract fun appPackages(): AppPackageDao
}
