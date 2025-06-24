package com.jackal.group.tfx.gau.util;

import lombok.extern.slf4j.Slf4j;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Slf4j
public class DatetimeUtil {
    private static final SimpleDateFormat GMT_MILLI_SEC_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
    /**
     * 2025-06-23T16:00:00.000+08:00
    */
    private static final SimpleDateFormat FMT_ZONED = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
    /**
     * 2025-06-23T16:00:00.000000Z
    */
    public static final DateTimeFormatter OLLAMA_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'");
    public static String formatOllamaTime(OffsetDateTime time) {
        return time.atZoneSameInstant(ZoneId.of("UTC")).format(OLLAMA_TIME_FMT);
    }

    private DatetimeUtil() {}

    public static long gmtMilliSecondsToTime(String dateTimeStr) {
        // Convert the timestamp string GMT_MILLI_SEC_FMT to long time milliseconds. 
        try {
            if (dateTimeStr.contains("Z")) {
                return GMT_MILLI_SEC_FMT.parse(dateTimeStr).getTime();
            } else {
                if (dateTimeStr.matches(".*\\+\\d{2}:\\d{2}$")) {
                    return FMT_ZONED.parse(dateTimeStr.replaceAll("(\\+\\d{2}):(\\d{2})$", "$1$2")).getTime();
                } else {
                    return FMT_ZONED.parse(dateTimeStr).getTime();
                }
            }
        } catch (ParseException e) {
            log.error("Failed to parse date time string to GMT seconds (gmtMilli): {}", dateTimeStr);
            return -1;
        }
    }
}
