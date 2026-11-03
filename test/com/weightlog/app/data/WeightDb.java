package com.weightlog.app.data;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * TEST-ONLY stub of {@code WeightDb}.
 *
 * <p>The real {@code WeightDb} imports {@code android.database.*} / {@code android.content.*},
 * so it cannot be compiled by this JDK-only harness. {@code Measurement.toJson()} however
 * calls the pure helper {@code WeightDb.isoTime(...)}, so the harness compiles this minimal
 * stand-in with the same signature to satisfy javac. Only {@code isoTime} is implemented,
 * matching the production formatting logic in {@code src/com/weightlog/app/data/WeightDb.java};
 * nothing here touches a database and this class is never packaged into the APK.
 */
public final class WeightDb {

    private WeightDb() {
    }

    /** Same contract as the production helper: ISO-8601 local time + numeric offset. */
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
}
