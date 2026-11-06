package br.com.contaslt.data.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Registro (tabela `records`) ou pendência de envio (tabela `outbox`, mesma forma). */
@Entity(tableName = "records")
public class RecordEntity {
    @PrimaryKey
    @NonNull
    public String id = "";
    @NonNull
    public String entity = "";
    @ColumnInfo(name = "data")
    public String data;
    public boolean deleted;
    public long updatedAt;
}
