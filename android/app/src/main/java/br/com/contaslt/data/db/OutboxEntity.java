package br.com.contaslt.data.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "outbox", indices = {@Index("updatedAt")})
public class OutboxEntity {
    @PrimaryKey
    @NonNull
    public String id = "";
    @NonNull
    public String entity = "";
    public String data;
    public boolean deleted;
    public long updatedAt;
}
