package com.moses.monitor.index;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Computes the time-bucket key for a given instant and granularity, matching
 * the "2026-09-11_12:00" format described in project_specification.md.
 */
public final class BucketKeyUtil {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH:mm")
            .withZone(ZoneOffset.UTC);

    private BucketKeyUtil() {
    }

    public static Instant floorToBucket(Instant instant, int granularityMinutes) {
        long epochMinute = instant.getEpochSecond() / 60;
        long flooredMinute = (epochMinute / granularityMinutes) * granularityMinutes;
        return Instant.ofEpochSecond(flooredMinute * 60);
    }

    public static String bucketKey(Instant instant, int granularityMinutes) {
        return FORMAT.format(floorToBucket(instant, granularityMinutes));
    }

    /** All bucket keys covering [from, to], inclusive. */
    public static List<String> bucketKeysInRange(Instant from, Instant to, int granularityMinutes) {
        List<String> keys = new ArrayList<>();
        Instant cursor = floorToBucket(from, granularityMinutes);
        Instant end = floorToBucket(to, granularityMinutes);
        long stepSeconds = granularityMinutes * 60L;
        while (!cursor.isAfter(end)) {
            keys.add(FORMAT.format(cursor));
            cursor = cursor.plusSeconds(stepSeconds);
        }
        return keys;
    }
}
