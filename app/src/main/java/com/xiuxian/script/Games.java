package com.xiuxian.script;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 游戏注册表：把「显示名」和「包名」一一绑定。
 *
 * 为什么必须用包名：这些是换皮游戏，玩法脚本完全一样，但包名不同、
 * 桌面名字还常常很像（天途 / 仙命决 / 天途修仙…）。如果按应用名去模糊匹配，
 * 装了两个以上就必然启动错的那一个。所以启动一律走包名精确匹配。
 *
 * 新增游戏只需要在 GAMES 里加一行，格式：{显示名, 包名, 版本备注}
 */
public class Games {

    /** {显示名, 包名, 版本备注} —— 包名必须是从 apk 里读出来的真实包名 */
    public static final String[][] GAMES = new String[][]{
            {"天途", "com.tt.spgw.qg", "1.0.4"},
    };

    private static final String PREFS = "xiuxian_games";
    private static final String KEY_SELECTED = "selected_index";

    public static int count() {
        return GAMES.length;
    }

    public static String name(int i) {
        return (i >= 0 && i < GAMES.length) ? GAMES[i][0] : "";
    }

    public static String pkg(int i) {
        return (i >= 0 && i < GAMES.length) ? GAMES[i][1] : "";
    }

    public static String note(int i) {
        return (i >= 0 && i < GAMES.length && GAMES[i].length > 2) ? GAMES[i][2] : "";
    }

    /** 读取上次选中的游戏下标，越界自动回到第 0 个 */
    public static int selectedIndex(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            int i = sp.getInt(KEY_SELECTED, 0);
            if (i < 0 || i >= GAMES.length) {
                i = 0;
            }
            return i;
        } catch (Throwable t) {
            return 0;
        }
    }

    public static void setSelectedIndex(Context ctx, int i) {
        if (i < 0 || i >= GAMES.length) {
            return;
        }
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            sp.edit().putInt(KEY_SELECTED, i).apply();
        } catch (Throwable ignored) {
        }
    }

    public static String selectedPkg(Context ctx) {
        return pkg(selectedIndex(ctx));
    }

    public static String selectedName(Context ctx) {
        return name(selectedIndex(ctx));
    }

    /** 这个包名在当前设备上装了没有 */
    public static boolean isInstalled(Context ctx, int i) {
        String p = pkg(i);
        if (p == null || p.isEmpty()) {
            return false;
        }
        try {
            ctx.getPackageManager().getPackageInfo(p, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 下拉列表用：显示名 · 版本，未安装的加标记 */
    public static String[] labels(Context ctx) {
        String[] out = new String[GAMES.length];
        for (int i = 0; i < GAMES.length; i++) {
            String n = name(i);
            String v = note(i);
            String base = v.isEmpty() ? n : (n + " · " + v);
            out[i] = isInstalled(ctx, i) ? base : (base + " [未安装]");
        }
        return out;
    }
}
