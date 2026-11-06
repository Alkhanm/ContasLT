package br.com.contaslt.data.db;

import java.util.List;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

@Dao
public interface RecordDao {

    @Query("SELECT * FROM records")
    List<RecordEntity> all();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void putRecords(List<RecordEntity> rows);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void putOutbox(List<OutboxEntity> rows);

    @Query("SELECT * FROM outbox WHERE id IN (:ids)")
    List<OutboxEntity> outboxByIds(List<String> ids);

    @Query("DELETE FROM outbox WHERE id IN (:ids)")
    void deleteOutbox(List<String> ids);

    @Query("SELECT * FROM outbox ORDER BY updatedAt LIMIT :limit")
    List<OutboxEntity> pending(int limit);

    @Query("SELECT COUNT(*) FROM outbox")
    int pendingCount();

    @Query("SELECT value FROM meta WHERE `key` = :key")
    String meta(String key);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void putMeta(MetaEntity row);
}
