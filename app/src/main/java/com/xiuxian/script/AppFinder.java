package com.xiuxian.script;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.List;

/**
 * 按应用名（桌面显示名）找到并启动目标 App。
 * 匹配顺序：1. 名称完全一致  2. 名称包含  3. 回退到配置里手填的包名。
 * 注意：AndroidManifest 里必须声明 QUERY_ALL_PACKAGES，否则 Android 11+ 查不到其它应用。
 */
public class AppFinder {

    /** 只在结果里做匹配，不再二次启动 */
    public static String findPackage(Context ctx, String appName, String fallbackPkg) {
        if (ctx == null) {
            return null;
        }
        PackageManager pm = ctx.getPackageManager();
        String target = appName == null ? "" : appName.trim();
        String fb = fallbackPkg == null ? "" : fallbackPkg.trim();

        // 1. 先在所有有桌面入口的应用里找
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = null;
        try {
            list = pm.queryIntentActivities(main, 0);
        } catch (Throwable t) {
            Logger.e("查询已安装应用失败: " + t);
        }

        if (list != null && !target.isEmpty()) {
            String containsHit = null;
            for (ResolveInfo ri : list) {
                if (ri == null || ri.activityInfo == null || ri.activityInfo.applicationInfo == null) {
                    continue;
                }
                ApplicationInfo ai = ri.activityInfo.applicationInfo;
                CharSequence label = ai.loadLabel(pm);
                String name = label == null ? "" : label.toString().trim();
                if (name.equalsIgnoreCase(target)) {
                    Logger.i("应用名精确匹配成功: " + name + " -> " + ai.packageName);
                    return ai.packageName;
                }
                if (containsHit == null && name.contains(target)) {
                    containsHit = ai.packageName;
                    Logger.i("应用名包含匹配: " + name + " -> " + ai.packageName);
                }
            }
            if (containsHit != null) {
                return containsHit;
            }
        }

        // 2. 回退：手填包名（只要装了就认）
        if (!fb.isEmpty()) {
            try {
                pm.getPackageInfo(fb, 0);
                Logger.w("按名称未匹配到，使用手填包名: " + fb);
                return fb;
            } catch (Throwable t) {
                Logger.e("手填包名也不存在: " + fb);
            }
        }

        Logger.e("找不到应用: " + target);
        return null;
    }

    /** 找到并用桌面入口启动 */
    public static boolean launch(Context ctx, String appName, String fallbackPkg) {
        String pkg = findPackage(ctx, appName, fallbackPkg);
        if (pkg == null) {
            return false;
        }
        try {
            Intent it = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (it == null) {
                Logger.e("该应用没有桌面启动入口: " + pkg);
                return false;
            }
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            ctx.startActivity(it);
            Logger.i("已启动: " + pkg);
            return true;
        } catch (Throwable t) {
            Logger.e("启动失败: " + t);
            return false;
        }
    }
}
