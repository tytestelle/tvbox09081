package com.github.tvbox.osc.util;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class FileLogger {
    private static final String LOG_DIR = "logs";
    private static FileLogger instance;
    private File logFile;
    private SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    // 启动时间戳，用于所有日志文件命名（格式：yyyyMMdd_HHmmss）
    private static String startTimestamp;

    private FileLogger(Context context) {
        File filesDir = context.getFilesDir();
        File logDir = new File(filesDir, LOG_DIR);
        if (!logDir.exists()) {
            logDir.mkdirs();
        }
        // 如果还未生成时间戳，则生成（保证单例初始化时生成）
        if (startTimestamp == null) {
            startTimestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        }
        // 日志文件名：log_<timestamp>.txt
        logFile = new File(logDir, "log_" + startTimestamp + ".txt");
    }

    public static synchronized void init(Context context) {
        if (instance == null) {
            instance = new FileLogger(context);
        }
    }

    /**
     * 获取本次启动的时间戳字符串，供其他模块使用（例如 EPG 映射日志）
     */
    public static String getStartTimestamp() {
        return startTimestamp;
    }

    public static void write(String tag, String msg) {
        if (instance == null) return;
        instance._write(tag, msg);
    }

    public static void write(String tag, String msg, Throwable tr) {
        if (instance == null) return;
        instance._write(tag, msg + "\n" + android.util.Log.getStackTraceString(tr));
    }

    private void _write(String tag, String msg) {
        try {
            String time = dateFormat.format(new Date());
            String line = time + " [" + tag + "] " + msg + "\n";
            FileWriter fw = new FileWriter(logFile, true);
            PrintWriter pw = new PrintWriter(fw);
            pw.print(line);
            pw.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static String getLogFilePath() {
        return instance != null ? instance.logFile.getAbsolutePath() : null;
    }
}
