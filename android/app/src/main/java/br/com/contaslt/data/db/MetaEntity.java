package br.com.contaslt.data.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "meta")
public class MetaEntity {
    @PrimaryKey
    @NonNull
    public String key = "";
    public String value;
}
