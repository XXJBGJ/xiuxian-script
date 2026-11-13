package com.xiuxian.script;

import android.content.Context;
import android.provider.Settings;

/**
 * 用 root 一次性把两个权限授予好，之后重启 App、重启模拟器都不用手动再开。
 *
 * 为什么原来每次都要重新打开：
 *   无障碍服务是系统服务，一旦 App 进程被关闭/被系统回收，服务实例就断了；
 *   而安卓的「无障碍开关」本身是持久化的，真正让它掉的是有些系统在
 *   应用被强停时会顺带把服务从启用列表里摘掉。悬浮窗权限同理，
 *   部分定制系统会把 appops 记录在应用被强制停止后重置。
 *
 * 解决办法（雷电模拟器默认带 root，正好可用）：
 *   1. 用 su 执行 settings put secure enabled_accessibility_services，
 *      把本应用的服务塞进启用列表（已存在就不重复加），并把总开关置 1；
 *   2. 用 su 执行 appops set <包名> SYSTEM_ALERT_WINDOW allow，授予悬浮窗；
 *   3. 每次 App 启动、以及开机广播里再执行一次，做到「静默自愈」。
 *
 * 全部操作失败也不会崩：拿不到 root 就静默跳过，仍然走原来手动授权的路径。
 */
public class RootGrant {

    /** 是否处于「正在检查/授权」状态，避免并发重复执行 */
    private static volatile boolean busy = false;

    /** 上次自愈是否成功，供界面显示 */
    private static volatile boolean lastOk = false;

    /**
     * 一次性补齐两个权限。
     *
     * @return true 表示两个权限现在都是就绪的
     */
    public static boolean ensureAll(Context c) {
        if (c == null) {
            return false;
        }
        if (busy) {
            return lastOk;
        }
        busy = true;
        try {
            // 【构建 128】先只用「纯读」接口判断：两个权限都已经就绪就直接返回，
            // 完全不执行 su。以前每次进 App / 每次点启动都会走一遍 su，
            // 于是「已授予超级用户权限」的授权提示反复弹出。现在只要权限齐了永不弹。
            if (isAccessibilityOn(c) && canDrawOverlay(c)) {
                lastOk = true;
                return true;
            }
            boolean acc = grantAccessibility(c);
            boolean ovl = grantOverlay(c);
            lastOk = acc && ovl;
            if (lastOk) {
                Logger.i("权限自愈完成：无障碍已启用、悬浮窗已允许（以后不用再手动开）");
            } else if (acc || ovl) {
                Logger.w("权限自愈部分成功：无障碍=" + acc + " 悬浮窗=" + ovl);
            }
            return lastOk;
        } finally {
            busy = false;
        }
    }

    /**
     * 把本应用的无障碍服务写进系统启用列表。
     *
     * 关键点：不能直接覆盖，要先读出原来已启用的服务，避免把别的应用的无障碍关掉。
     */
    public static boolean grantAccessibility(Context c) {
        try {
            String pkg = c.getPackageName();
            String comp = pkg + "/" + AutoService.class.getName();
            String compShort = pkg + "/." + AutoService.class.getSimpleName();

            // 已经是开的，直接返回
            if (isAccessibilityOn(c)) {
                return true;
            }

            String cur = sh("settings get secure enabled_accessibility_services");
            cur = cur == null ? "" : cur.trim();
            if ("null".equalsIgnoreCase(cur)) {
                cur = "";
            }
            boolean has = cur.toLowerCase().contains(comp.toLowerCase())
                    || cur.toLowerCase().contains(compShort.toLowerCase());
            String next;
            if (has) {
                next = cur;
            } else if (cur.length() == 0) {
                next = comp;
            } else {
                next = cur + ":" + comp;
            }

            // 写入启用列表 + 打开总开关
            sh("settings put secure enabled_accessibility_services '" + next + "'");
            sh("settings put secure accessibility_enabled 1");
            // 部分系统还需要给服务单独放行
            sh("settings put secure accessibility_button_mode 0");

            Thread.sleep(350);
            boolean ok = isAccessibilityOn(c);
            if (ok) {
                Logger.i("已用 root 自动启用无障碍服务（只需授权这一次）");
            }
            return ok;
        } catch (Throwable t) {
            Logger.w("root 启用无障碍失败: " + t.getClass().getSimpleName());
            return false;
        }
    }

    /** 用 appops 授予「显示在应用上层」 */
    public static boolean grantOverlay(Context c) {
        try {
            if (canDrawOverlay(c)) {
                return true;
            }
            String pkg = c.getPackageName();
            sh("appops set " + pkg + " SYSTEM_ALERT_WINDOW allow");
            // 部分安卓9/10 机型还要写这个键，双保险
            sh("appops set " + pkg + " android:system_alert_window allow");
            Thread.sleep(300);
            boolean ok = canDrawOverlay(c);
            if (ok) {
                Logger.i("已用 root 自动授予悬浮窗权限（只需授权这一次）");
            }
            return ok;
        } catch (Throwable t) {
            Logger.w("root 授予悬浮窗失败: " + t.getClass().getSimpleName());
            return false;
        }
    }

    /** 无障碍是否已启用（直接读系统启用列表，比看服务连没连更准） */
    public static boolean isAccessibilityOn(Context c) {
        try {
            String want = c.getPackageName() + "/" + AutoService.class.getName();
            String on = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (on == null) {
                return false;
            }
            String lower = on.toLowerCase();
            return lower.contains(want.toLowerCase())
                    || lower.contains((c.getPackageName() + "/." + AutoService.class.getSimpleName())
                    .toLowerCase());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 悬浮窗权限是否已授予 */
    public static boolean canDrawOverlay(Context c) {
        try {
            if (android.os.Build.VERSION.SDK_INT < 23) {
                return true;
            }
            return Settings.canDrawOverlays(c);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 执行一条 root 命令，返回标准输出；没有 root 就返回空串 */
    private static String sh(String cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append((char) 10);
            }
            br.close();
            p.waitFor();
            return sb.toString();
        } catch (Throwable t) {
            return "";
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
