package com.bobbypfreely.lpbf;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.PrintWriter;
import java.io.StringWriter;

public class CrashLogger {
    private static final String PREFS = "crash_log_prefs";
    private static final String KEY = "last_crash";

    public static void install(Context appContext) {
        Thread.UncaughtExceptionHandler defaultHandler =
                Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                StringWriter sw = new StringWriter();
                throwable.printStackTrace(new PrintWriter(sw));
                SharedPreferences prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                prefs.edit().putString(KEY, sw.toString()).apply();
            } catch (Throwable ignored) {
                // never let the logger itself crash the crash handler
            }
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            }
        });
    }

    public static String getLastCrash(Context appContext) {
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getString(KEY, null);
    }

    public static void clear(Context appContext) {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply();
    }
}
