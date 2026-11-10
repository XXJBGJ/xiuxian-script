package com.xiuxian.script;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 游戏注册表 + 扫描。
 *
 * 核心原则：一切以「包名」为准，绝不用应用名匹配。
 * 这些是换皮游戏（天途 / 仙命决 / …），玩法脚本完全一样但包名不同，
 * 桌面名字还常常很像。按名字模糊查找一定会启动错，所以启动只认包名。
 *
 * 列表来源有两层：
 * 1) 内置种子 SEED（天途 + 仙命决各渠道），保证开箱可用；
 * 2) 用户点「扫描并选择游戏」后，从设备已安装应用里勾选，结果持久化保存。
 * 用户不需要提供 APK、也不需要自己去查包名。
 */
public class Games {

    private static final String PREFS = "xiuxian_games";
    private static final String KEY_LIST = "game_list";
    private static final String KEY_SELECTED = "selected_index";

    /** 内置种子 {显示名, 包名, 版本备注}，首次启动写入列表 */
    private static final String[][] SEED = new String[][]{
            {"天途", "com.tt.spgw.qg", "1.0.4"},
            {"仙命决·4399", "com.gm.xmj.m4399", "2.5"},
            {"仙命决·百度", "com.gm.xmj.g.baidu", "1.0.1"},
            {"仙命决·怪猫", "com.knight.kungfu", "1.3.2"},
            {"仙命决·九游", "com.gm.xmj.aligames", "1.7"},
            {"仙命决·聚乐", "com.xmj.wf.htc", "3.0"},
            {"仙命决·模拟器", "com.xmj.twgmb1", "1.0.0"},
            {"仙命决·小米", "com.gm.xmj.mi", "1.2.4"},
    };

    public static class Game {
        public String name;
        public String pkg;
        public String note;

        public Game(String name, String pkg, String note) {
            this.name = name == null ? "" : name;
            this.pkg = pkg == null ? "" : pkg;
            this.note = note == null ? "" : note;
        }
    }

    /** 进程内缓存，避免每次读盘；name(int) 这类无 Context 的接口也能用 */
    private static List<Game> CACHE;

    private static List<Game> ensure(Context ctx) {
        if (CACHE == null) {
            CACHE = load(ctx);
        }
        return CACHE;
    }

    // ================= 读写持久化 =================

    private static List<Game> load(Context ctx) {
        List<Game> out = new ArrayList<Game>();
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String s = sp.getString(KEY_LIST, null);
            if (s == null) {
                // 第一次运行：写入内置种子
                for (String[] r : SEED) {
                    out.add(new Game(r[0], r[1], r.length > 2 ? r[2] : ""));
                }
                save(ctx, out);
                return out;
            }
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Game(o.optString("name"), o.optString("pkg"), o.optString("note")));
            }
        } catch (Throwable t) {
            Logger.e("读取游戏列表失败: " + t);
        }
        return out;
    }

    private static void save(Context ctx, List<Game> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Game g : list) {
                JSONObject o = new JSONObject();
                o.put("name", g.name);
                o.put("pkg", g.pkg);
                o.put("note", g.note);
                arr.put(o);
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_LIST, arr.toString()).apply();
        } catch (Throwable t) {
            Logger.e("保存游戏列表失败: " + t);
        }
    }

    // ================= 基础访问 =================

    public static int count() {
        return CACHE == null ? 0 : CACHE.size();
    }

    public static String name(int i) {
        if (CACHE == null || i < 0 || i >= CACHE.size()) {
            return "";
        }
        return CACHE.get(i).name;
    }

    public static String pkg(int i) {
        if (CACHE == null || i < 0 || i >= CACHE.size()) {
            return "";
        }
        return CACHE.get(i).pkg;
    }

    public static String note(int i) {
        if (CACHE == null || i < 0 || i >= CACHE.size()) {
            return "";
        }
        return CACHE.get(i).note;
    }

    public static int indexOfPkg(Context ctx, String p) {
        if (p == null) {
            return -1;
        }
        List<Game> l = ensure(ctx);
        for (int i = 0; i < l.size(); i++) {
            if (p.equals(l.get(i).pkg)) {
                return i;
            }
        }
        return -1;
    }

    // ================= 选中项 =================

    public static int selectedIndex(Context ctx) {
        try {
            int n = ensure(ctx).size();
            if (n <= 0) {
                return 0;
            }
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            int i = sp.getInt(KEY_SELECTED, 0);
            if (i < 0 || i >= n) {
                i = 0;
            }
            return i;
        } catch (Throwable t) {
            return 0;
        }
    }

    public static void setSelectedIndex(Context ctx, int i) {
        int n = count();
        if (i < 0 || i >= n) {
            return;
        }
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putInt(KEY_SELECTED, i).apply();
        } catch (Throwable ignored) {
        }
    }

    public static String selectedPkg(Context ctx) {
        ensure(ctx);
        return pkg(selectedIndex(ctx));
    }

    public static String selectedName(Context ctx) {
        ensure(ctx);
        return name(selectedIndex(ctx));
    }

    // ================= 安装状态 / 下拉文案 =================

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

    public static String[] labels(Context ctx) {
        List<Game> l = ensure(ctx);
        String[] out = new String[l.size()];
        for (int i = 0; i < l.size(); i++) {
            Game g = l.get(i);
            String base = g.note.isEmpty() ? g.name : (g.name + " · " + g.note);
            out[i] = isInstalled(ctx, i) ? base : (base + " [未安装]");
        }
        return out;
    }

    // ================= 扫描设备上已安装的可启动应用 =================

    /**
     * 列出设备上所有带桌面图标的第三方应用（排除自己）。
     * 用户勾选即可，无需自己查包名。
     */
    public static List<Game> scanInstalled(Context ctx) {
        List<Game> out = new ArrayList<Game>();
        try {
            PackageManager pm = ctx.getPackageManager();
            Intent it = new Intent(Intent.ACTION_MAIN);
            it.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> ris = pm.queryIntentActivities(it, 0);
            String self = ctx.getPackageName();
            java.util.HashSet<String> seen = new java.util.HashSet<String>();
            for (ResolveInfo ri : ris) {
                if (ri == null || ri.activityInfo == null) {
                    continue;
                }
                String p = ri.activityInfo.packageName;
                if (p == null || p.isEmpty() || p.equals(self)) {
                    continue;
                }
                if (!seen.add(p)) {
                    continue;
                }
                String label;
                try {
                    label = String.valueOf(ri.loadLabel(pm));
                } catch (Throwable t) {
                    label = p;
                }
                String ver = "";
                try {
                    String v = pm.getPackageInfo(p, 0).versionName;
                    if (v != null) {
                        ver = v;
                    }
                } catch (Throwable ignored) {
                }
                out.add(new Game(label, p, ver));
            }
            Collections.sort(out, new Comparator<Game>() {
                @Override
                public int compare(Game a, Game b) {
                    return a.name.compareToIgnoreCase(b.name);
                }
            });
        } catch (Throwable t) {
            Logger.e("扫描已安装应用失败: " + t);
        }
        return out;
    }

    /**
     * 应用扫描结果：勾选 = 加入列表，取消勾选 = 从列表移除。
     * 不在候选里的条目（例如已卸载的）保持原样。
     */
    public static void applySelection(Context ctx, List<Game> cands, boolean[] checked) {
        List<Game> cur = new ArrayList<Game>(ensure(ctx));
        for (int i = 0; i < cands.size(); i++) {
            Game c = cands.get(i);
            int pos = -1;
            for (int j = 0; j < cur.size(); j++) {
                if (cur.get(j).pkg.equals(c.pkg)) {
                    pos = j;
                    break;
                }
            }
            if (checked[i] && pos < 0) {
                cur.add(c);
            } else if (!checked[i] && pos >= 0) {
                cur.remove(pos);
            }
        }
        CACHE = cur;
        save(ctx, cur);
        Logger.i("游戏列表已更新，共 " + cur.size() + " 个");
    }
}

/**
 * 账号管理：按「包名」给每个游戏单独存一套账号密码。
 *
 * 要点：
 * 1) 天途、仙命决的 7 个渠道互相独立，切游戏就是切另一套账号；
 * 2) 账号密码用 & 隔开，例如  13800000000&abc123  ；
 * 3) 只存在本机 SharedPreferences 里，不上传、不同步；
 *    清单里 allowBackup=false，所以换手机后不会跟着账号恢复，必须重新输入；
 * 4) 每条账号带勾选状态，启动脚本只跑勾选的那些，没勾的直接跳过。
 */
class Accounts {

    private static final String PREFS = "xiuxian_accounts";

    /** 账号与密码的分隔符 */
    public static final String SEP = "&";

    /** 界面是否明文显示密码（只在内存里，不落盘） */
    private static boolean showPass = false;

    public static boolean isShowPass() {
        return showPass;
    }

    public static void setShowPass(boolean b) {
        showPass = b;
    }

    public static class Acc {
        public String user = "";
        public String pass = "";
        public boolean checked = false;

        public Acc() {
        }

        public Acc(String u, String p, boolean c) {
            user = u == null ? "" : u;
            pass = p == null ? "" : p;
            checked = c;
        }

        /** 还原成 账号&密码 一行文本 */
        public String raw() {
            return user + SEP + pass;
        }

        /** 列表里显示的文本 */
        public String show() {
            if (pass.length() == 0) {
                return user;
            }
            if (showPass) {
                return user + "  " + pass;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < pass.length(); i++) {
                sb.append('*');
            }
            return user + "  " + sb.toString();
        }
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ================= 读写 =================

    /** 读某个游戏（包名）的账号列表；读不到就返回空表 */
    public static List<Acc> list(Context ctx, String pkg) {
        List<Acc> out = new ArrayList<Acc>();
        if (ctx == null || pkg == null || pkg.length() == 0) {
            return out;
        }
        try {
            String s = sp(ctx).getString(keyOf(pkg), null);
            if (s == null || s.length() == 0) {
                return out;
            }
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Acc a = new Acc();
                a.user = o.optString("u", "");
                a.pass = o.optString("p", "");
                a.checked = o.optBoolean("c", false);
                if (a.user.length() > 0) {
                    out.add(a);
                }
            }
        } catch (Throwable t) {
            Logger.e("读取账号失败: " + t);
        }
        return out;
    }

    /** 写回某个游戏的账号列表 */
    public static void save(Context ctx, String pkg, List<Acc> list) {
        if (ctx == null || pkg == null || pkg.length() == 0) {
            return;
        }
        try {
            JSONArray arr = new JSONArray();
            if (list != null) {
                for (Acc a : list) {
                    if (a == null || a.user.length() == 0) {
                        continue;
                    }
                    JSONObject o = new JSONObject();
                    o.put("u", a.user);
                    o.put("p", a.pass);
                    o.put("c", a.checked);
                    arr.put(o);
                }
            }
            sp(ctx).edit().putString(keyOf(pkg), arr.toString()).apply();
        } catch (Throwable t) {
            Logger.e("保存账号失败: " + t);
        }
    }

    private static String keyOf(String pkg) {
        return "acc_" + pkg.replace('.', '_');
    }

    // ================= 解析 =================

    /**
     * 解析一行「账号&密码」。
     * 只在第一个 & 处切分，所以密码里带 & 也不会被切坏。
     * 返回 null 表示这行是空的、需要忽略。
     */
    public static Acc parseLine(String line) {
        if (line == null) {
            return null;
        }
        String s = line.trim();
        if (s.length() == 0) {
            return null;
        }
        int p = s.indexOf(SEP);
        String u;
        String pw;
        if (p < 0) {
            // 只写了账号没写密码，也允许，密码留空
            u = s;
            pw = "";
        } else {
            u = s.substring(0, p).trim();
            pw = s.substring(p + 1).trim();
        }
        if (u.length() == 0) {
            return null;
        }
        return new Acc(u, pw, true);
    }

    // ================= 增删改 =================

    /**
     * 添加一个账号（或一段用换行分隔的多行文本）。
     * 同名的已存在就覆盖密码，不重复添加。
     * 返回实际新增/更新的条数。
     */
    public static int addOrUpdate(Context ctx, String pkg, String text) {
        if (text == null || text.trim().length() == 0) {
            return 0;
        }
        List<Acc> cur = list(ctx, pkg);
        int n = 0;
        String[] lines = text.split("\n");
        for (String ln : lines) {
            Acc a = parseLine(ln);
            if (a == null) {
                continue;
            }
            int pos = indexOfUser(cur, a.user);
            if (pos >= 0) {
                cur.get(pos).pass = a.pass;
                cur.get(pos).checked = true;
            } else {
                cur.add(a);
            }
            n++;
        }
        save(ctx, pkg, cur);
        Logger.i("账号已保存: 新增/更新 " + n + " 条，当前共 " + cur.size() + " 条");
        return n;
    }

    /** 删除第 idx 条 */
    public static void remove(Context ctx, String pkg, int idx) {
        List<Acc> cur = list(ctx, pkg);
        if (idx < 0 || idx >= cur.size()) {
            return;
        }
        String u = cur.get(idx).user;
        cur.remove(idx);
        save(ctx, pkg, cur);
        Logger.i("已删除账号: " + u);
    }

    /** 批量删除：把所有勾选的账号一次删掉，返回删除条数 */
    public static int removeChecked(Context ctx, String pkg) {
        List<Acc> cur = list(ctx, pkg);
        int n = 0;
        for (int i = cur.size() - 1; i >= 0; i--) {
            if (cur.get(i).checked) {
                cur.remove(i);
                n++;
            }
        }
        save(ctx, pkg, cur);
        Logger.i("批量删除账号 " + n + " 条");
        return n;
    }

    /** 清空该游戏所有账号 */
    public static int removeAll(Context ctx, String pkg) {
        int n = list(ctx, pkg).size();
        save(ctx, pkg, new ArrayList<Acc>());
        Logger.i("已清空该游戏账号 " + n + " 条");
        return n;
    }

    /** 切换某条账号的勾选状态 */
    public static void setChecked(Context ctx, String pkg, int idx, boolean checked) {
        List<Acc> cur = list(ctx, pkg);
        if (idx < 0 || idx >= cur.size()) {
            return;
        }
        cur.get(idx).checked = checked;
        save(ctx, pkg, cur);
    }

    /** 全选 / 全不选 */
    public static void setAllChecked(Context ctx, String pkg, boolean checked) {
        List<Acc> cur = list(ctx, pkg);
        for (Acc a : cur) {
            a.checked = checked;
        }
        save(ctx, pkg, cur);
    }

    private static int indexOfUser(List<Acc> l, String u) {
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).user.equals(u)) {
                return i;
            }
        }
        return -1;
    }

    // ================= 供脚本引擎调用 =================

    /** 总量 */
    public static int count(Context ctx, String pkg) {
        return list(ctx, pkg).size();
    }

    /** 勾选数量 */
    public static int checkedCount(Context ctx, String pkg) {
        int n = 0;
        for (Acc a : list(ctx, pkg)) {
            if (a.checked) {
                n++;
            }
        }
        return n;
    }

    /**
     * 本次要跑哪些账号：只返回勾选过的，未勾选的直接跳过。
     * 顺序与列表一致，保证每次跑的顺序稳定。
     */
    public static List<Acc> checkedList(Context ctx, String pkg) {
        List<Acc> out = new ArrayList<Acc>();
        for (Acc a : list(ctx, pkg)) {
            if (a.checked) {
                out.add(a);
            }
        }
        return out;
    }
}
