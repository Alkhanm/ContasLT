package br.com.contaslt.data.db;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

/** Banco local de um usuário (um arquivo por usuário: trocar de conta não mistura dados). */
@Database(entities = {RecordEntity.class, OutboxEntity.class, MetaEntity.class}, version = 1, exportSchema = false)
public abstract class ContasDatabase extends RoomDatabase {

    public abstract RecordDao dao();

    public static String fileName(long userId) {
        return "contaslt-u" + userId + ".db";
    }

    public static ContasDatabase open(Context context, long userId) {
        return Room.databaseBuilder(context, ContasDatabase.class, fileName(userId)).build();
    }
}
