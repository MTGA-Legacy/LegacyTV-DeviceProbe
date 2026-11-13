package com.mtgalegacy.deviceprobe;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** A small in-memory log retained for the local HTTP diagnostics endpoint. */
public final class RingLog {
    public static final int MAX_ENTRIES = 300;
    private static final int MAX_MESSAGE_LENGTH = 2048;
    private static final int MAX_EXCEPTION_LENGTH = 4096;

    private final ArrayDeque<Entry> entries = new ArrayDeque<Entry>(MAX_ENTRIES);
    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US);

    public synchronized void debug(String message) {
        add("debug", message, null);
    }

    public synchronized void info(String message) {
        add("info", message, null);
    }

    public synchronized void warn(String message) {
        add("warn", message, null);
    }

    public synchronized void error(String message, Throwable error) {
        add("error", message, error);
    }

    private void add(String level, String message, Throwable error) {
        String safeMessage = trim(message == null ? "" : message, MAX_MESSAGE_LENGTH);
        String safeException = error == null ? null : trim(error.toString(), MAX_EXCEPTION_LENGTH);
        if (entries.size() >= MAX_ENTRIES) {
            entries.removeFirst();
        }
        entries.addLast(new Entry(timeFormat.format(new Date()), level, safeMessage, safeException));
    }

    public synchronized void clear() {
        entries.clear();
    }

    public synchronized JSONArray toJson(int requestedLimit) throws JSONException {
        int limit = Math.max(1, Math.min(MAX_ENTRIES, requestedLimit));
        List<Entry> snapshot = new ArrayList<Entry>(entries);
        int start = Math.max(0, snapshot.size() - limit);
        JSONArray result = new JSONArray();
        for (int i = start; i < snapshot.size(); i++) {
            result.put(snapshot.get(i).toJson());
        }
        return result;
    }

    private static String trim(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "…";
    }

    private static final class Entry {
        final String time;
        final String level;
        final String message;
        final String exception;

        Entry(String time, String level, String message, String exception) {
            this.time = time;
            this.level = level;
            this.message = message;
            this.exception = exception;
        }

        JSONObject toJson() throws JSONException {
            JSONObject object = new JSONObject();
            object.put("time", time);
            object.put("level", level);
            object.put("message", message);
            if (exception != null) {
                object.put("exception", exception);
            }
            return object;
        }
    }
}
