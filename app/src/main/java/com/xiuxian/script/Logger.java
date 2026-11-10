package com.xiuxian.script;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * 全局日志：界面滚动显示 + 同时落盘到 App 私有目录。
 * 日志文件位置：Android/data/com.xiuxian.script/files/logs/run_yyyyMMdd.log
 */
public class Logger {

    private static final int MAX_MEMORY_LINES = 1000;

    private static final ArrayList<String> lines = new ArrayList<>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static final SimpleDateFormat DAY = new SimpleDateFormat("yyyyMMdd", Locale.US);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static File logDir;
    private static String currentDay = "";
    private static BufferedWriter writer;
    private static Listener listener;

    public interface Listener {
        void onLog(String line);
    }

    public static synchronized void init(Context context) {
        if (logDir != null) {
            return;
        }
        try {
            Context app = context.getApplicationContext();
            File base = app.getExternalFilesDir(null);
            if (base == null) {
                base = app.getFilesDir();
            }
            logDir = new File(base, "logs");
            if (!logDir.exists()) {
                logDir.mkdirs();
            }
        } catch (Throwable ignored) {
        }
    }

    public static void setListener(Listener l) {
        listener = l;
    }

    public static synchronized String[] snapshot() {
        return lines.toArray(new String[0]);
    }

    public static synchronized void clear() {
        lines.clear();
    }

    public static String logDirPath() {
        return logDir == null ? "未初始化" : logDir.getAbsolutePath();
    }

    /** 当天的日志文件（按天切分），未初始化返回 null */
    public static File currentFile() {
        if (logDir == null) {
            return null;
        }
        return new File(logDir, "run_" + DAY.format(new Date()) + ".log");
    }

    /**
     * 取「完整日志文本」，供一键复制 / 导出使用。
     *
     * 先读当天落盘的文件：这样即使 App 重启过、内存日志被清掉，之前的记录也还在。
     * 文件不存在时退回内存里的行。
     */
    public static String fullText() {
        StringBuilder sb = new StringBuilder();
        File f = currentFile();
        if (f != null && f.exists() && f.length() > 0) {
            java.io.BufferedReader r = null;
            try {
                r = new java.io.BufferedReader(new java.io.InputStreamReader(
                        new java.io.FileInputStream(f), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append((char) 10);
                }
            } catch (Throwable ignored) {
            } finally {
                if (r != null) {
                    try {
                        r.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        if (sb.length() == 0) {
            String[] mem = snapshot();
            if (mem != null) {
                for (String l : mem) {
                    sb.append(l).append((char) 10);
                }
            }
        }
        return sb.toString();
    }

    public static void i(String msg) {
        write("I", msg);
    }

    public static void w(String msg) {
        write("W", msg);
    }

    public static void e(String msg) {
        write("E", msg);
    }

    private static void write(String level, String msg) {
        final String line = TIME.format(new Date()) + " [" + level + "] " + msg;
        synchronized (Logger.class) {
            lines.add(line);
            while (lines.size() > MAX_MEMORY_LINES) {
                lines.remove(0);
            }
            appendToFile(line);
        }
        final Listener l = listener;
        if (l != null) {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    l.onLog(line);
                }
            });
        }
    }

    private static void appendToFile(String line) {
        if (logDir == null) {
            return;
        }
        try {
            String day = DAY.format(new Date());
            if (!day.equals(currentDay) || writer == null) {
                closeWriter();
                currentDay = day;
                File f = new File(logDir, "run_" + day + ".log");
                writer = new BufferedWriter(new OutputStreamWriter(
                        new FileOutputStream(f, true), "UTF-8"));
            }
            writer.write(line);
            writer.newLine();
            writer.flush();
        } catch (Throwable t) {
            closeWriter();
        }
    }

    private static void closeWriter() {
        if (writer != null) {
            try {
                writer.close();
            } catch (Throwable ignored) {
            }
            writer = null;
        }
    }
}
