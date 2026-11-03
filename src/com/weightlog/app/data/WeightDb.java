package com.weightlog.app.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 本地 SQLite（weightlog.db，version 1）：一张 measurement 表。
 *
 * 所有方法都是同步阻塞的，调用方负责不在主线程执行（JSBridge 线程 / 导出线程）。
 * 卸载 App 会清空数据库，重要数据请先导出。
 */
public class WeightDb extends SQLiteOpenHelper {

    private static final String TAG = "WeightLog";
    public static final String DB_NAME = "weightlog.db";
    public static final int DB_VERSION = 1;

    private static final String TABLE = "measurement";
    /** 「最近出过错」的时间窗：stateJson 用它决定是否给 UI 报 dbError */
    private static final long RECENT_ERROR_WINDOW_MS = 60000L;

    /** 最近一次 DB 错误时间 / 摘要（不含 bind key / token 等敏感内容） */
    private volatile long lastErrorAt;
    private volatile String lastError = "";

    public WeightDb(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "measured_at_ms INTEGER NOT NULL,"
                + "tz_offset_min INTEGER NOT NULL,"
                + "profile_id INTEGER NOT NULL DEFAULT 1,"
                + "weight_kg REAL NOT NULL,"
                + "impedance_low_ohm REAL,"
                + "impedance_high_ohm REAL,"
                + "heart_rate INTEGER,"
                + "stabilized INTEGER NOT NULL DEFAULT 1,"
                + "body_fat_pct REAL,"
                + "bmi REAL,"
                + "bmr_kcal INTEGER,"
                + "calc_version INTEGER NOT NULL DEFAULT 1,"
                + "profile_height_cm INTEGER,"
                + "profile_age INTEGER,"
                + "profile_sex TEXT,"
                + "source TEXT NOT NULL DEFAULT 'ble',"
                + "note TEXT,"
                + "raw_json TEXT,"
                + "created_at_ms INTEGER NOT NULL"
                + ")");
        db.execSQL("CREATE INDEX idx_measurement_time ON " + TABLE + " (measured_at_ms DESC)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.i(TAG, "db upgrade " + oldVersion + " -> " + newVersion);
        // 按版本逐步迁移：以后加字段/索引时在 migrate() 里补一个 case，再 bump DB_VERSION
        for (int v = oldVersion; v < newVersion; v++) {
            migrate(db, v);
        }
    }

    /** 从版本 v 迁移到 v+1。目前只有 v1，还没有 schema 变更。 */
    private static void migrate(SQLiteDatabase db, int version) {
        switch (version) {
            case 1:
                // v1 → v2：no schema change yet（预留给未来的字段/索引变更）
                break;
            default:
                Log.w(TAG, "db migrate: unknown version " + version);
                break;
        }
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 不删数据：只记录，避免降级安装把用户记录清空
        Log.w(TAG, "db downgrade " + oldVersion + " -> " + newVersion);
    }

    /**
     * 在一个事务里执行 body（异常时回滚）。
     * body 必须自己吞掉可恢复错误，否则事务整体回滚。
     */
    public void transaction(Runnable body) {
        if (body == null) {
            return;
        }
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                body.run();
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (Exception e) {
            markError("transaction failed");
            Log.e(TAG, "db transaction failed", e);
        }
    }

    /** 记录一次 DB 错误（供 stateJson 暴露 dbError）。 */
    private void markError(String message) {
        lastError = message != null ? message : "db error";
        lastErrorAt = System.currentTimeMillis();
    }

    /** 最近 60 秒内是否发生过 DB 错误（写入失败 / 查询失败等）。 */
    public boolean hasRecentError() {
        long at = lastErrorAt;
        return at > 0 && System.currentTimeMillis() - at <= RECENT_ERROR_WINDOW_MS;
    }

    // ========== 写入 ==========

    public long insert(Measurement m) {
        if (m == null) {
            return -1;
        }
        if (m.createdAtMs <= 0) {
            m.createdAtMs = System.currentTimeMillis();
        }
        ContentValues v = new ContentValues();
        v.put("measured_at_ms", m.measuredAtMs);
        v.put("tz_offset_min", m.tzOffsetMin);
        v.put("profile_id", m.profileId);
        v.put("weight_kg", m.weightKg);
        putDouble(v, "impedance_low_ohm", m.impedanceLowOhm);
        putDouble(v, "impedance_high_ohm", m.impedanceHighOhm);
        putInteger(v, "heart_rate", m.heartRate);
        v.put("stabilized", m.stabilized ? 1 : 0);
        putDouble(v, "body_fat_pct", m.bodyFatPct);
        putDouble(v, "bmi", m.bmi);
        putInteger(v, "bmr_kcal", m.bmrKcal);
        v.put("calc_version", m.calcVersion);
        putInteger(v, "profile_height_cm", m.profileHeightCm);
        putInteger(v, "profile_age", m.profileAge);
        v.put("profile_sex", m.profileSex);
        v.put("source", m.source != null ? m.source : Measurement.SOURCE_BLE);
        v.put("note", m.note);
        v.put("raw_json", m.rawJson);
        v.put("created_at_ms", m.createdAtMs);
        try {
            return getWritableDatabase().insert(TABLE, null, v);
        } catch (Exception e) {
            markError("insert failed");
            Log.e(TAG, "db insert failed", e);
            return -1;
        }
    }

    /** 修改备注；返回是否成功。 */
    public boolean updateNote(long id, String note) {
        try {
            ContentValues v = new ContentValues();
            if (note == null || note.length() == 0) {
                v.putNull("note");
            } else {
                v.put("note", note);
            }
            int rows = getWritableDatabase().update(TABLE, v, "id=?",
                    new String[]{String.valueOf(id)});
            return rows > 0;
        } catch (Exception e) {
            markError("updateNote failed");
            Log.e(TAG, "db updateNote failed", e);
            return false;
        }
    }

    public boolean delete(long id) {
        try {
            int rows = getWritableDatabase().delete(TABLE, "id=?",
                    new String[]{String.valueOf(id)});
            return rows > 0;
        } catch (Exception e) {
            markError("delete failed");
            Log.e(TAG, "db delete failed", e);
            return false;
        }
    }

    /** 删除某一来源的记录（演示数据用）。 */
    public int deleteBySource(String source) {
        try {
            return getWritableDatabase().delete(TABLE, "source=?",
                    new String[]{source != null ? source : ""});
        } catch (Exception e) {
            markError("deleteBySource failed");
            Log.e(TAG, "db deleteBySource failed", e);
            return 0;
        }
    }

    /** 档案变化后重算单条记录的体成分与档案快照。 */
    public boolean updateComposition(long id, Double bodyFatPct, Double bmi, Integer bmrKcal,
                                     Integer heightCm, Integer age, String sex, int calcVersion) {
        try {
            ContentValues v = new ContentValues();
            putDouble(v, "body_fat_pct", bodyFatPct);
            putDouble(v, "bmi", bmi);
            putInteger(v, "bmr_kcal", bmrKcal);
            putInteger(v, "profile_height_cm", heightCm);
            putInteger(v, "profile_age", age);
            v.put("profile_sex", sex);
            v.put("calc_version", calcVersion);
            return getWritableDatabase().update(TABLE, v, "id=?",
                    new String[]{String.valueOf(id)}) > 0;
        } catch (Exception e) {
            markError("updateComposition failed");
            Log.e(TAG, "db updateComposition failed", e);
            return false;
        }
    }

    public void clearAll() {
        try {
            getWritableDatabase().delete(TABLE, null, null);
        } catch (Exception e) {
            markError("clear failed");
            Log.e(TAG, "db clear failed", e);
        }
    }

    // ========== 查询 ==========

    public Measurement getLast() {
        List<Measurement> list = query(null, 1, 0);
        return list.isEmpty() ? null : list.get(0);
    }

    public Measurement getPrevious() {
        List<Measurement> list = query(null, 1, 1);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 最近 days 天；days <= 0 表示全部。按时间倒序。 */
    public List<Measurement> list(int days) {
        Long since = null;
        if (days > 0) {
            since = Long.valueOf(System.currentTimeMillis() - (long) days * 86400000L);
        }
        return query(since, 0, 0);
    }

    public int count() {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + TABLE, null);
            if (c.moveToFirst()) {
                return c.getInt(0);
            }
        } catch (Exception e) {
            markError("count failed");
            Log.e(TAG, "db count failed", e);
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return 0;
    }

    private List<Measurement> query(Long sinceMs, int limit, int offset) {
        List<Measurement> result = new ArrayList<Measurement>();
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(TABLE);
        List<String> args = new ArrayList<String>();
        if (sinceMs != null) {
            sql.append(" WHERE measured_at_ms>=?");
            args.add(String.valueOf(sinceMs.longValue()));
        }
        sql.append(" ORDER BY measured_at_ms DESC, id DESC");
        if (limit > 0) {
            sql.append(" LIMIT ").append(limit);
        } else if (offset > 0) {
            sql.append(" LIMIT -1");
        }
        if (offset > 0) {
            sql.append(" OFFSET ").append(offset);
        }
        Cursor c = null;
        try {
            String[] selectionArgs = args.isEmpty() ? null : args.toArray(new String[args.size()]);
            c = getReadableDatabase().rawQuery(sql.toString(), selectionArgs);
            while (c.moveToNext()) {
                result.add(read(c));
            }
        } catch (Exception e) {
            markError("query failed");
            Log.e(TAG, "db query failed", e);
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return result;
    }

    private static Measurement read(Cursor c) {
        Measurement m = new Measurement();
        m.id = c.getLong(c.getColumnIndexOrThrow("id"));
        m.measuredAtMs = c.getLong(c.getColumnIndexOrThrow("measured_at_ms"));
        m.tzOffsetMin = c.getInt(c.getColumnIndexOrThrow("tz_offset_min"));
        m.profileId = c.getInt(c.getColumnIndexOrThrow("profile_id"));
        m.weightKg = c.getDouble(c.getColumnIndexOrThrow("weight_kg"));
        int idx = c.getColumnIndexOrThrow("impedance_low_ohm");
        m.impedanceLowOhm = c.isNull(idx) ? null : Double.valueOf(c.getDouble(idx));
        idx = c.getColumnIndexOrThrow("impedance_high_ohm");
        m.impedanceHighOhm = c.isNull(idx) ? null : Double.valueOf(c.getDouble(idx));
        idx = c.getColumnIndexOrThrow("heart_rate");
        m.heartRate = c.isNull(idx) ? null : Integer.valueOf(c.getInt(idx));
        m.stabilized = c.getInt(c.getColumnIndexOrThrow("stabilized")) != 0;
        idx = c.getColumnIndexOrThrow("body_fat_pct");
        m.bodyFatPct = c.isNull(idx) ? null : Double.valueOf(c.getDouble(idx));
        idx = c.getColumnIndexOrThrow("bmi");
        m.bmi = c.isNull(idx) ? null : Double.valueOf(c.getDouble(idx));
        idx = c.getColumnIndexOrThrow("bmr_kcal");
        m.bmrKcal = c.isNull(idx) ? null : Integer.valueOf(c.getInt(idx));
        m.calcVersion = c.getInt(c.getColumnIndexOrThrow("calc_version"));
        idx = c.getColumnIndexOrThrow("profile_height_cm");
        m.profileHeightCm = c.isNull(idx) ? null : Integer.valueOf(c.getInt(idx));
        idx = c.getColumnIndexOrThrow("profile_age");
        m.profileAge = c.isNull(idx) ? null : Integer.valueOf(c.getInt(idx));
        idx = c.getColumnIndexOrThrow("profile_sex");
        m.profileSex = c.isNull(idx) ? null : c.getString(idx);
        m.source = c.getString(c.getColumnIndexOrThrow("source"));
        idx = c.getColumnIndexOrThrow("note");
        m.note = c.isNull(idx) ? null : c.getString(idx);
        idx = c.getColumnIndexOrThrow("raw_json");
        m.rawJson = c.isNull(idx) ? null : c.getString(idx);
        m.createdAtMs = c.getLong(c.getColumnIndexOrThrow("created_at_ms"));
        return m;
    }

    // ========== 时间工具 ==========

    /** 指定时刻的本地时区偏移（分钟）。补录历史记录时用记录时刻的偏移，而不是「现在」的偏移。 */
    public static int tzOffsetMinAt(long epochMs) {
        return TimeZone.getDefault().getOffset(epochMs) / 60000;
    }

    public static int currentTzOffsetMin() {
        return tzOffsetMinAt(System.currentTimeMillis());
    }

    /** epoch 毫秒 + 记录时区 → ISO 8601（2026-10-21T21:03:00+08:00）。 */
    public static String isoTime(long epochMs, int tzOffsetMin) {
        long localMs = epochMs + (long) tzOffsetMin * 60000L;
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        StringBuilder sb = new StringBuilder(format.format(new Date(localMs)));
        int offset = tzOffsetMin;
        sb.append(offset < 0 ? '-' : '+');
        offset = Math.abs(offset);
        sb.append(pad2(offset / 60)).append(':').append(pad2(offset % 60));
        return sb.toString();
    }

    private static String pad2(int value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }

    private static void putDouble(ContentValues v, String key, Double value) {
        if (value != null) {
            v.put(key, value.doubleValue());
        } else {
            v.putNull(key);
        }
    }

    private static void putInteger(ContentValues v, String key, Integer value) {
        if (value != null) {
            v.put(key, value.intValue());
        } else {
            v.putNull(key);
        }
    }
}
