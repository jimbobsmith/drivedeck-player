package org.schabi.newpipe.mediasync

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Insert
import androidx.room.OnConflictStrategy

@Entity(tableName = "dd_media_sync")
data class MediaSyncState(@PrimaryKey val id: Int = 1, val body: String)
@Dao
interface MediaSyncStateDAO {
    @Query("SELECT body FROM dd_media_sync WHERE id=1") fun read(): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun write(state: MediaSyncState)
    @Query("DELETE FROM dd_media_sync") fun clear()
}
