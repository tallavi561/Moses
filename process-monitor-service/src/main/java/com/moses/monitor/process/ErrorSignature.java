package com.moses.monitor.process;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns an error message into the "kind" of error it is, so that "order 1234 failed" and
 * "order 5678 failed" are counted together. Removes the parts that change from one occurrence to
 * the next: ids, numbers, addresses, quoted values.
 */
final class ErrorSignature {

    private static final int MAX_LENGTH = 200;

    private static final Pattern UUID = Pattern.compile(
            "\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEX = Pattern.compile("\\b(0x)?[0-9a-f]{12,}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern IP = Pattern.compile("\\b\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?\\b");
    private static final Pattern QUOTED = Pattern.compile("'[^']*'|\"[^\"]*\"");
    private static final Pattern NUMBER = Pattern.compile("\\d+(\\.\\d+)?");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private ErrorSignature() {
    }

    static String of(String message) {
        if (message == null) {
            return "";
        }
        String firstLine = message.strip();
        int newline = firstLine.indexOf('\n');
        if (newline >= 0) {
            firstLine = firstLine.substring(0, newline);
        }
        String s = UUID.matcher(firstLine).replaceAll("<id>");
        s = HEX.matcher(s).replaceAll("<id>");
        s = IP.matcher(s).replaceAll("<ip>");
        s = QUOTED.matcher(s).replaceAll("<value>");
        s = NUMBER.matcher(s).replaceAll("<n>");
        s = SPACES.matcher(s).replaceAll(" ").strip().toLowerCase(Locale.ROOT);
        return s.length() > MAX_LENGTH ? s.substring(0, MAX_LENGTH) : s;
    }
}
