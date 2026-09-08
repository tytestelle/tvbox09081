package com.github.tvbox.osc.data;

import android.annotation.SuppressLint;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;

import androidx.annotation.NonNull;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.cache.EpgChannelDao;
import com.github.tvbox.osc.cache.EpgDataDao;
import com.github.tvbox.osc.util.EpgUtil;
import com.github.tvbox.osc.util.FileUtils;

import java.io.File;
import java.io.IOException;

public class AppDataManager {
    private static final int DB_FILE_VERSION = 3;
    private static final String DB_NAME = "tvbox";
    private static AppDataManager manager;
    private static AppDataBase dbInstance;

    private AppDataManager() {}

    public static void init() {
        if (manager == null) {
            synchronized (AppDataManager.class) {
                if (manager == null) {
                    manager = new AppDataManager();
                }
            }
        }
        // 自动初始化EPG数据库（异步）
        EpgUtil.init();
    }

    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `epg_channel` ("
                + "`name` TEXT NOT NULL, `logo` TEXT, `epgid` TEXT, `aliases` TEXT, "
                + "`updateTime` INTEGER NOT NULL, PRIMARY KEY(`name`))"
            );
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_epg_channel_epgid` ON `epg_channel` (`epgid`)"
            );
        }
    };

    static final Migration MIGRATION_2_3_EPG = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `epg_data` ("
                + "`channelName` TEXT NOT NULL, `date` TEXT NOT NULL, "
                + "`title` TEXT, `start` TEXT, `end` TEXT, "
                + "`startTime` INTEGER NOT NULL, `endTime` INTEGER NOT NULL, "
                + "`idx` INTEGER NOT NULL, "
                + "PRIMARY KEY(`channelName`, `date`, `idx`))"
            );
        }
    };

    static final Migration MIGRATION_1_2_OLD = new Migration(1, 2) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            try {
                database.execSQL("ALTER TABLE sourceState ADD COLUMN tidSort TEXT");
            } catch (SQLiteException e) { e.printStackTrace(); }
        }
    };

    static final Migration MIGRATION_2_3_OLD = new Migration(2, 3) {
        @SuppressLint("Range")
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `vodRecordTmp` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `vodId` TEXT, `updateTime` INTEGER NOT NULL, `sourceKey` TEXT, `data` BLOB, `dataJson` TEXT, `testMigration` INTEGER NOT NULL)");
            Cursor cursor = database.query("SELECT * FROM vodRecord");
            int id, vodId;
            long updateTime;
            String sourceKey, dataJson;
            while (cursor.moveToNext()) {
                id = cursor.getInt(cursor.getColumnIndex("id"));
                vodId = cursor.getInt(cursor.getColumnIndex("vodId"));
                updateTime = cursor.getLong(cursor.getColumnIndex("updateTime"));
                sourceKey = cursor.getString(cursor.getColumnIndex("sourceKey"));
                dataJson = cursor.getString(cursor.getColumnIndex("dataJson"));
                database.execSQL("INSERT INTO vodRecordTmp (id, vodId, updateTime, sourceKey, dataJson, testMigration) VALUES ('" + id + "', '" + vodId + "', '" + updateTime + "', '" + sourceKey + "', '" + dataJson + "',0 )");
            }
            database.execSQL("DROP TABLE vodRecord");
            database.execSQL("ALTER TABLE vodRecordTmp RENAME TO vodRecord");
        }
    };

    static final Migration MIGRATION_3_4_OLD = new Migration(3, 4) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            try {
                database.execSQL("ALTER TABLE vodRecord ADD COLUMN dataJson TEXT");
            } catch (SQLiteException e) { e.printStackTrace(); }
        }
    };

    static final Migration MIGRATION_4_5_OLD = new Migration(4, 5) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            try {
                database.execSQL("ALTER TABLE localSource ADD COLUMN type INTEGER NOT NULL DEFAULT 0");
            } catch (SQLiteException e) { e.printStackTrace(); }
        }
    };

    static String dbPath() {
        return DB_NAME + ".v" + DB_FILE_VERSION + ".db";
    }

    public static AppDataBase get() {
        if (manager == null) throw new RuntimeException("AppDataManager is no init");
        if (dbInstance == null)
            dbInstance = Room.databaseBuilder(App.getInstance(), AppDataBase.class, dbPath())
                    .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3_EPG, MIGRATION_2_3_OLD)
                    .addCallback(new RoomDatabase.Callback() {
                        @Override public void onCreate(@NonNull SupportSQLiteDatabase db) { super.onCreate(db); }
                        @Override public void onOpen(@NonNull SupportSQLiteDatabase db) { super.onOpen(db); }
                    }).allowMainThreadQueries().build();
        return dbInstance;
    }

    public EpgDataDao getEpgDataDao() {
        AppDataBase db = get();
        return db != null ? db.getEpgDataDao() : null;
    }

    public EpgChannelDao getEpgChannelDao() {
        AppDataBase db = get();
        return db != null ? db.getEpgChannelDao() : null;
    }

    public static boolean backup(File path) throws IOException {
        if (dbInstance != null && dbInstance.isOpen()) dbInstance.close();
        File db = App.getInstance().getDatabasePath(dbPath());
        if (db.exists()) {
            FileUtils.copyFile(db, path);
            return true;
        }
        return false;
    }

    public static boolean restore(File path) throws IOException {
        if (dbInstance != null && dbInstance.isOpen()) dbInstance.close();
        File db = App.getInstance().getDatabasePath(dbPath());
        if (db.exists()) db.delete();
        if (!db.getParentFile().exists()) db.getParentFile().mkdirs();
        FileUtils.copyFile(path, db);
        return true;
    }
}
