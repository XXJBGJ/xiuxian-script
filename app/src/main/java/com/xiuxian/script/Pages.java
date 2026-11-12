package com.xiuxian.script;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 日常 / 仙榜 / 活动 三个页面的内容渲染。
 *
 * 日常页顶部是四个配置按钮（从左到右）：
 *   我的配置 / 推荐配置 / 自定义配置 / 保存配置
 *
 * 三种生效方式互斥，同一时间只有一个在起作用：
 *   自定义配置 —— 自由勾选，随时改；不保存时每次启动就是最后一次的勾选
 *   我的配置   —— 之前用「保存配置」存下的任务清单，勾选锁定不可改，可改名、可删除
 *   推荐配置   —— 内置 6 个官方配置（万仙盟-10/20/35/55/80、一件全选）
 * 若「我的配置」里已经选了配置，每次启动沿用上次选中的那一个。
 *
 * 仙榜：限时活动 / 主榜 / 释放榜 三个区，具体内容稍后补。
 * 活动：目前只有「领地征讨」。
 *
 * 全部用代码搭界面，不新增布局文件。
 */
public class Pages {

    private static final String COLOR_TEXT = "#FF1F1F1F";
    private static final String COLOR_MUTED = "#FF8A8A8A";
    private static final String COLOR_OK = "#FF2E8B57";
    private static final String COLOR_WARN = "#FFC9822F";
    private static final String COLOR_LINE = "#FFE2E2E2";
    private static final String COLOR_CARD = "#FFFFFFFF";
    private static final String COLOR_SOFT = "#FFF7F7F7";

    /** 自定义勾选状态的存储名 */
    private static final String SP_DAILY = "daily_sel";
    /** 配置文件（我的配置、当前模式、排序方式）的存储名 */
    private static final String SP_CFG = "daily_cfg";

    static final String MODE_CUSTOM = "custom";
    static final String MODE_MINE = "mine";
    static final String MODE_REC = "rec";

    // ======================= 日常：任务树 =======================

    /** 一个可勾选的节点：父项/子项同一套结构 */
    static class Node {
        String path = "";
        String name = "";
        boolean checked = false;
        final List<Node> children = new ArrayList<Node>();

        boolean isParent() {
            return !children.isEmpty();
        }

        /** 整棵子树全部勾上 */
        boolean allChecked() {
            if (!isParent()) {
                return checked;
            }
            for (int i = 0; i < children.size(); i++) {
                if (!children.get(i).allChecked()) {
                    return false;
                }
            }
            return true;
        }

        /** 子树里有没有任意一项勾上 */
        boolean anyChecked() {
            if (checked) {
                return true;
            }
            for (int i = 0; i < children.size(); i++) {
                if (children.get(i).anyChecked()) {
                    return true;
                }
            }
            return false;
        }
    }

    private static Node node(String name, Node... kids) {
        Node x = new Node();
        x.name = name;
        for (int i = 0; i < kids.length; i++) {
            x.children.add(kids[i]);
        }
        return x;
    }

    /**
     * 构建日常任务的两大区。
     * 凡间：坊市、行囊、演武、慕仙境、宗门、吐纳、修炼、仙盟、洞府、闯荡
     *   仙盟 → 建造、排行榜、仙盟事务、天宫幻境、宴会、除魔
     *   洞府 → 灵山、酒肆、结缘、器室、丹炉、仙界聘书
     *   闯荡 → 仙缘、出窍、寻宝、福地、凶兽
     * 上界：仙都、天庭、门派、云游、刑天战场、万古遗迹、万仙盟
     *   仙都 → 遗迹、论道、仙界灵宠、游历、圣地寻访
     *   门派 → 集市、驯兽园、掌门大厅、门派历练
     *   云游 → 灵契、九幽、锁妖塔、小世界、虚空异变
     */
    private static List<Node> buildDailyTree() {
        List<Node> roots = new ArrayList<Node>();

        Node fanjian = node("凡间",
                node("坊市"),
                node("行囊"),
                node("演武"),
                node("慕仙境"),
                node("宗门"),
                node("吐纳"),
                node("修炼"),
                node("仙盟",
                        node("建造"), node("排行榜"), node("仙盟事务"),
                        node("天宫幻境"), node("宴会"), node("除魔")),
                node("洞府",
                        node("灵山"), node("酒肆"), node("结缘"),
                        node("器室"), node("丹炉"), node("仙界聘书")),
                node("闯荡",
                        node("仙缘"), node("出窍"), node("寻宝"),
                        node("福地"), node("凶兽")));

        Node shangjie = node("上界",
                node("仙都",
                        node("遗迹"), node("论道"), node("仙界灵宠"),
                        node("游历"), node("圣地寻访")),
                node("天庭"),
                node("门派",
                        node("集市"), node("驯兽园"), node("掌门大厅"), node("门派历练")),
                node("云游",
                        node("灵契"), node("九幽"), node("锁妖塔"),
                        node("小世界"), node("虚空异变")),
                node("刑天战场"),
                node("万古遗迹"),
                node("万仙盟"));

        roots.add(fanjian);
        roots.add(shangjie);
        return roots;
    }

    /** 给每个节点算出唯一路径，作为勾选状态与配置里的键 */
    private static void assignPath(Node n, String parent) {
        n.path = parent.isEmpty() ? n.name : (parent + "/" + n.name);
        for (int i = 0; i < n.children.size(); i++) {
            assignPath(n.children.get(i), n.path);
        }
    }

    private static void collect(Node n, List<Node> out) {
        out.add(n);
        for (int i = 0; i < n.children.size(); i++) {
            collect(n.children.get(i), out);
        }
    }

    private static void collectLeaves(Node n, List<String> out) {
        if (n.children.isEmpty()) {
            out.add(n.path);
            return;
        }
        for (int i = 0; i < n.children.size(); i++) {
            collectLeaves(n.children.get(i), out);
        }
    }

    private static void collectCheckedLeaves(Node n, List<String> out) {
        if (n.children.isEmpty()) {
            if (n.checked) {
                out.add(n.path);
            }
            return;
        }
        for (int i = 0; i < n.children.size(); i++) {
            collectCheckedLeaves(n.children.get(i), out);
        }
    }

    private static Node findPath(List<Node> roots, String path) {
        if (path == null || path.length() == 0) {
            return null;
        }
        for (int i = 0; i < roots.size(); i++) {
            Node hit = findPathIn(roots.get(i), path);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static Node findPathIn(Node n, String path) {
        if (path.equals(n.path)) {
            return n;
        }
        for (int i = 0; i < n.children.size(); i++) {
            Node hit = findPathIn(n.children.get(i), path);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static List<String> leavesUnder(List<Node> roots, String parentPath) {
        List<String> out = new ArrayList<String>();
        Node target = findPath(roots, parentPath);
        if (target != null) {
            collectLeaves(target, out);
        }
        return out;
    }

    private static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    // ======================= 勾选状态读写 =======================

    /** 从本机读回自定义勾选 */
    private static void loadCustom(SharedPreferences sp, List<Node> roots) {
        String csv = sp.getString("custom_tasks", "");
        applyTasks(roots, csv);
    }

    /** 保存当前勾选为「自定义」 */
    private static void saveCustom(SharedPreferences sp, List<Node> roots) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < roots.size(); i++) {
            collectCheckedLeaves(roots.get(i), out);
        }
        sp.edit().putString("custom_tasks", join(out)).apply();
    }

    /** 按任务清单覆盖勾选：先清空，再逐个点亮，最后让父项跟着子项走 */
    private static void applyTasks(List<Node> roots, String csv) {
        for (int i = 0; i < roots.size(); i++) {
            clearAll(roots.get(i));
        }
        if (csv != null && csv.trim().length() > 0) {
            String[] parts = csv.split(",");
            for (int i = 0; i < parts.length; i++) {
                String p = parts[i].trim();
                if (p.length() == 0) {
                    continue;
                }
                Node n = findPath(roots, p);
                if (n != null) {
                    n.checked = true;
                }
            }
        }
        for (int i = 0; i < roots.size(); i++) {
            normalize(roots.get(i));
        }
    }

    private static void clearAll(Node n) {
        n.checked = false;
        for (int i = 0; i < n.children.size(); i++) {
            clearAll(n.children.get(i));
        }
    }

    /** 让父项的勾选状态跟着子项走 */
    private static void normalize(Node n) {
        if (!n.isParent()) {
            return;
        }
        for (int i = 0; i < n.children.size(); i++) {
            normalize(n.children.get(i));
        }
        n.checked = n.allChecked();
    }

    private static void setChecked(Node target, boolean checked) {
        applyDown(target, checked);
    }

    private static void applyDown(Node n, boolean checked) {
        n.checked = checked;
        for (int i = 0; i < n.children.size(); i++) {
            applyDown(n.children.get(i), checked);
        }
    }

    // ======================= 配置模型 =======================

    /** 一份配置：名字 + 保存时间 + 任务清单（叶子路径，逗号分隔） */
    static class Cfg {
        String name = "";
        long time = 0L;
        String tasks = "";
        int repeat = 0;
        String note = "";

        String toJson() {
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("name", name);
                o.put("time", time);
                o.put("tasks", tasks);
                o.put("repeat", repeat);
                o.put("note", note);
                return o.toString();
            } catch (Throwable t) {
                return "";
            }
        }

        static Cfg fromJson(String s) {
            try {
                org.json.JSONObject o = new org.json.JSONObject(s);
                Cfg c = new Cfg();
                c.name = o.optString("name", "");
                c.time = o.optLong("time", 0);
                c.tasks = o.optString("tasks", "");
                c.repeat = o.optInt("repeat", 0);
                c.note = o.optString("note", "");
                return c;
            } catch (Throwable t) {
                return null;
            }
        }

        /** 这份配置里有几项任务 */
        int count() {
            if (tasks == null || tasks.trim().length() == 0) {
                return 0;
            }
            String[] p = tasks.split(",");
            int c = 0;
            for (int i = 0; i < p.length; i++) {
                if (p[i].trim().length() > 0) {
                    c++;
                }
            }
            return c;
        }

        String timeText() {
            if (time <= 0) {
                return "";
            }
            java.text.SimpleDateFormat f =
                    new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US);
            return f.format(new java.util.Date(time));
        }
    }

    private static List<Cfg> loadMine(SharedPreferences cf) {
        List<Cfg> out = new ArrayList<Cfg>();
        String s = cf.getString("mine", "");
        if (s == null || s.length() == 0) {
            return out;
        }
        try {
            org.json.JSONArray arr = new org.json.JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                Cfg c = Cfg.fromJson(arr.optString(i, ""));
                if (c != null && c.name.length() > 0) {
                    out.add(c);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void saveMine(SharedPreferences cf, List<Cfg> list) {
        org.json.JSONArray arr = new org.json.JSONArray();
        for (int i = 0; i < list.size(); i++) {
            arr.put(list.get(i).toJson());
        }
        cf.edit().putString("mine", arr.toString()).apply();
    }

    private static Cfg findCfg(List<Cfg> list, String name) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).name.equals(name)) {
                return list.get(i);
            }
        }
        return null;
    }

    /**
     * 官方推荐配置：万仙盟-10/20/35/55/80，以及一件全选。
     *
     * 每个档位按明确的任务名单勾选，不再用「次数」当模糊参数。
     * 这里一律写完整路径，因为「遗迹」「行囊」「建造」等名字在树里
     * 可能出现在不同上级下面，只写名字很容易勾错节点。
     */
    private static List<Cfg> recommend(List<Node> roots) {
        List<Cfg> out = new ArrayList<Cfg>();

        // 10 档：建造、出窍、福地、凶兽、万仙盟
        out.add(rec("万仙盟-10", roots,
                "凡间/仙盟/建造",
                "凡间/闯荡/出窍",
                "凡间/闯荡/福地",
                "凡间/闯荡/凶兽",
                "上界/万仙盟"));

        // 20 档：20 档 + 行囊、器室、丹炉、灵契
        out.add(rec("万仙盟-20", roots,
                "凡间/行囊",
                "凡间/仙盟/建造",
                "凡间/洞府/器室",
                "凡间/洞府/丹炉",
                "凡间/闯荡/出窍",
                "凡间/闯荡/福地",
                "凡间/闯荡/凶兽",
                "上界/云游/灵契",
                "上界/万仙盟"));

        // 35 档：20 档 + 遗迹、掌门大厅、门派历练、九幽
        out.add(rec("万仙盟-35", roots,
                "凡间/行囊",
                "凡间/仙盟/建造",
                "凡间/洞府/器室",
                "凡间/洞府/丹炉",
                "凡间/闯荡/出窍",
                "凡间/闯荡/福地",
                "凡间/闯荡/凶兽",
                "上界/仙都/遗迹",
                "上界/门派/掌门大厅",
                "上界/门派/门派历练",
                "上界/云游/灵契",
                "上界/云游/九幽",
                "上界/万仙盟"));

        // 55 档：35 档 + 论道、虚空异变
        out.add(rec("万仙盟-55", roots,
                "凡间/行囊",
                "凡间/仙盟/建造",
                "凡间/洞府/器室",
                "凡间/洞府/丹炉",
                "凡间/闯荡/出窍",
                "凡间/闯荡/福地",
                "凡间/闯荡/凶兽",
                "上界/仙都/遗迹",
                "上界/仙都/论道",
                "上界/门派/掌门大厅",
                "上界/门派/门派历练",
                "上界/云游/灵契",
                "上界/云游/九幽",
                "上界/云游/虚空异变",
                "上界/万仙盟"));

        // 80 档：55 档 + 刑天战场
        out.add(rec("万仙盟-80", roots,
                "凡间/行囊",
                "凡间/仙盟/建造",
                "凡间/洞府/器室",
                "凡间/洞府/丹炉",
                "凡间/闯荡/出窍",
                "凡间/闯荡/福地",
                "凡间/闯荡/凶兽",
                "上界/仙都/遗迹",
                "上界/仙都/论道",
                "上界/门派/掌门大厅",
                "上界/门派/门派历练",
                "上界/云游/灵契",
                "上界/云游/九幽",
                "上界/云游/虚空异变",
                "上界/刑天战场",
                "上界/万仙盟"));

        Cfg all = new Cfg();
        all.name = "一件全选";
        List<String> every = new ArrayList<String>();
        for (int i = 0; i < roots.size(); i++) {
            collectLeaves(roots.get(i), every);
        }
        all.tasks = join(every);
        all.note = "全部 " + every.size() + " 项任务";
        out.add(all);
        return out;
    }

    /** 组装一个推荐配置；路径写错或节点不存在时直接跳过，不让它污染勾选 */
    private static Cfg rec(String name, List<Node> roots, String... paths) {
        Cfg c = new Cfg();
        c.name = name;
        List<String> hit = new ArrayList<String>();
        for (int i = 0; i < paths.length; i++) {
            Node n = findPath(roots, paths[i]);
            if (n != null && !n.isParent()) {
                hit.add(paths[i]);
            }
        }
        c.tasks = join(hit);
        c.note = "勾选 " + hit.size() + " 项任务";
        return c;
    }

    // ======================= 日常页 =======================

    public static void renderDaily(Context ctx, LinearLayout box) {
        box.removeAllViews();

        final List<Node> roots = buildDailyTree();
        for (int i = 0; i < roots.size(); i++) {
            assignPath(roots.get(i), "");
        }
        final SharedPreferences sp = ctx.getSharedPreferences(SP_DAILY, Context.MODE_PRIVATE);
        final SharedPreferences cf = ctx.getSharedPreferences(SP_CFG, Context.MODE_PRIVATE);

        String mode = cf.getString("mode", MODE_CUSTOM);
        boolean readOnly = false;
        String activeName = "";

        if (MODE_MINE.equals(mode)) {
            List<Cfg> mine = loadMine(cf);
            Cfg hit = findCfg(mine, cf.getString("mine_sel", ""));
            if (hit != null) {
                applyTasks(roots, hit.tasks);
                readOnly = true;
                activeName = hit.name;
            } else {
                mode = MODE_CUSTOM;
                cf.edit().putString("mode", MODE_CUSTOM).apply();
            }
        } else if (MODE_REC.equals(mode)) {
            List<Cfg> rec = recommend(roots);
            Cfg hit = findCfg(rec, cf.getString("rec_sel", ""));
            if (hit == null) {
                hit = rec.get(0);
            }
            applyTasks(roots, hit.tasks);
            readOnly = true;
            activeName = hit.name;
            cf.edit().putString("rec_sel", hit.name).apply();
        } else {
            loadCustom(sp, roots);
            mode = MODE_CUSTOM;
        }

        // ---- 顶部：四个配置按钮（我的配置 / 推荐配置 / 自定义配置 / 保存配置）----
        box.addView(configBar(ctx, box, roots, sp, cf, mode, activeName, readOnly));

        // ---- 当前生效的配置说明 ----
        TextView line = new TextView(ctx);
        if (MODE_MINE.equals(mode)) {
            line.setText("当前生效：我的配置 ·「" + activeName + "」  勾选已锁定");
            line.setTextColor(Color.parseColor(COLOR_OK));
        } else if (MODE_REC.equals(mode)) {
            line.setText("当前生效：推荐配置 ·「" + activeName + "」  勾选已锁定");
            line.setTextColor(Color.parseColor(COLOR_OK));
        } else {
            line.setText("当前生效：自定义配置 · 勾选随时可改，未保存时启动沿用最后一次勾选");
            line.setTextColor(Color.parseColor(COLOR_MUTED));
        }
        line.setTextSize(12);
        line.setPadding(dp(ctx, 2), 0, dp(ctx, 2), dp(ctx, 6));
        box.addView(line);

        // ---- 任务树 ----
        for (int i = 0; i < roots.size(); i++) {
            box.addView(buildSection(ctx, box, roots, sp, cf, roots.get(i), 0, readOnly));
        }

        box.addView(hint(ctx, "「我的配置」「推荐配置」「自定义配置」三者只能有一个生效；"
                + "「我的配置」里存下的清单不能改，只能改名或删除。"));
    }

    /** 四个配置按钮：横向等分，当前生效的那个高亮 */
    private static View configBar(final Context ctx, final LinearLayout pageBox,
                                  final List<Node> roots, final SharedPreferences sp,
                                  final SharedPreferences cf, final String mode,
                                  final String activeName, final boolean readOnly) {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(0, 0, 0, dp(ctx, 4));

        String[] names = new String[]{"我的配置", "推荐配置", "自定义配置", "保存配置"};
        for (int i = 0; i < names.length; i++) {
            // 用 TextView 承载按钮，避免 Material 主题给 Button 加背景着色后
            // 把自定义背景（高亮色）盖掉，导致看不出哪个配置正在生效
            TextView b = new TextView(ctx);
            b.setText(names[i]);
            b.setTextSize(12.5f);
            b.setGravity(Gravity.CENTER);
            b.setClickable(true);
            b.setFocusable(true);
            b.setPadding(0, dp(ctx, 8), 0, dp(ctx, 8));

            boolean on = (i == 0 && MODE_MINE.equals(mode))
                    || (i == 1 && MODE_REC.equals(mode))
                    || (i == 2 && MODE_CUSTOM.equals(mode));
            if (on) {
                b.setTextColor(Color.parseColor("#FFFFFFFF"));
                b.setTypeface(Typeface.DEFAULT_BOLD);
                GradientDrawable g = new GradientDrawable();
                g.setColor(Color.parseColor(COLOR_OK));
                g.setCornerRadius(dp(ctx, 6));
                b.setBackgroundDrawable(g);
            } else {
                b.setTextColor(Color.parseColor("#FF3A3A3A"));
                GradientDrawable g = new GradientDrawable();
                g.setColor(Color.parseColor(COLOR_SOFT));
                g.setCornerRadius(dp(ctx, 6));
                g.setStroke(Math.max(1, dp(ctx, 1)), Color.parseColor(COLOR_LINE));
                b.setBackgroundDrawable(g);
            }

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(ctx, 2), 0, dp(ctx, 2), 0);
            b.setLayoutParams(lp);

            final int idx = i;
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (idx == 0) {
                        showMineDialog(ctx, pageBox, roots, sp, cf);
                    } else if (idx == 1) {
                        showRecDialog(ctx, pageBox, roots, sp, cf);
                    } else if (idx == 2) {
                        if (MODE_CUSTOM.equals(mode)) {
                            toast(ctx, "已经是自定义配置，直接勾选即可");
                            return;
                        }
                        cf.edit().putString("mode", MODE_CUSTOM).apply();
                        renderDaily(ctx, pageBox);
                    } else {
                        showSaveDialog(ctx, pageBox, roots, sp, cf);
                    }
                }
            });
            bar.addView(b);
        }
        return bar;
    }

    // ======================= 我的配置：选择 / 改名 / 删除 =======================

    private static void showMineDialog(final Context ctx, final LinearLayout pageBox,
                                       final List<Node> roots, final SharedPreferences sp,
                                       final SharedPreferences cf) {
        final List<Cfg> mine = loadMine(cf);
        final String sort = cf.getString("sort", "time");

        if (mine.isEmpty()) {
            new AlertDialog.Builder(ctx)
                    .setTitle("我的配置")
                    .setMessage("还没有保存过配置。\n\n先在下面自由勾选任务，再点「保存配置」给它起个名字，"
                            + "就会出现在这里，以后一键切换。")
                    .setPositiveButton("去勾选任务", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            cf.edit().putString("mode", MODE_CUSTOM).apply();
                            renderDaily(ctx, pageBox);
                        }
                    })
                    .setNegativeButton("关闭", null)
                    .show();
            return;
        }

        // 按保存时间或名称排序
        if ("name".equals(sort)) {
            Collections.sort(mine, new Comparator<Cfg>() {
                @Override
                public int compare(Cfg a, Cfg b) {
                    return a.name.compareTo(b.name);
                }
            });
        } else {
            Collections.sort(mine, new Comparator<Cfg>() {
                @Override
                public int compare(Cfg a, Cfg b) {
                    if (a.time == b.time) {
                        return 0;
                    }
                    return a.time > b.time ? -1 : 1;
                }
            });
        }

        final String cur = cf.getString("mine_sel", "");
        final AlertDialog[] holder = new AlertDialog[1];

        ScrollView sv = new ScrollView(ctx);
        final LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4));
        sv.addView(list);

        // 排序切换
        LinearLayout sortBar = new LinearLayout(ctx);
        sortBar.setOrientation(LinearLayout.HORIZONTAL);
        sortBar.setPadding(dp(ctx, 4), dp(ctx, 6), dp(ctx, 4), dp(ctx, 2));
        TextView t1 = new TextView(ctx);
        t1.setText("排序方式：");
        t1.setTextSize(12.5f);
        t1.setTextColor(Color.parseColor(COLOR_MUTED));
        sortBar.addView(t1);

        sortBar.addView(sortChip(ctx, "时间排序", "time".equals(sort), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cf.edit().putString("sort", "time").apply();
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
                showMineDialog(ctx, pageBox, roots, sp, cf);
            }
        }));
        sortBar.addView(sortChip(ctx, "名称排序", "name".equals(sort), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cf.edit().putString("sort", "name").apply();
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
                showMineDialog(ctx, pageBox, roots, sp, cf);
            }
        }));
        list.addView(sortBar);

        for (int i = 0; i < mine.size(); i++) {
            final Cfg c = mine.get(i);
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor(c.name.equals(cur) ? "#FFF1F7F2" : COLOR_CARD));
            bg.setCornerRadius(dp(ctx, 6));
            bg.setStroke(Math.max(1, dp(ctx, 1)), Color.parseColor(COLOR_LINE));
            row.setBackgroundDrawable(bg);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = dp(ctx, 6);
            row.setLayoutParams(rlp);

            LinearLayout info = new LinearLayout(ctx);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView nm = new TextView(ctx);
            nm.setText((c.name.equals(cur) ? "● " : "") + c.name);
            nm.setTextSize(14);
            nm.setTypeface(Typeface.DEFAULT_BOLD);
            nm.setTextColor(Color.parseColor(COLOR_TEXT));
            info.addView(nm);

            TextView meta = new TextView(ctx);
            String m = c.count() + " 项任务";
            if (c.note != null && c.note.length() > 0) {
                m = m + " · " + c.note;
            }
            if (c.timeText().length() > 0) {
                m = m + " · " + c.timeText();
            }
            meta.setText(m);
            meta.setTextSize(11.5f);
            meta.setTextColor(Color.parseColor(COLOR_MUTED));
            info.addView(meta);

            row.addView(info);

            TextView rename = smallBtn(ctx, "改名");
            rename.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (holder[0] != null) {
                        holder[0].dismiss();
                    }
                    showRenameDialog(ctx, pageBox, roots, sp, cf, c);
                }
            });
            row.addView(rename);

            TextView del = smallBtn(ctx, "删除");
            del.setTextColor(Color.parseColor("#FFB00020"));
            del.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (holder[0] != null) {
                        holder[0].dismiss();
                    }
                    confirmDelete(ctx, pageBox, roots, sp, cf, c);
                }
            });
            row.addView(del);

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cf.edit().putString("mode", MODE_MINE)
                            .putString("mine_sel", c.name).apply();
                    if (holder[0] != null) {
                        holder[0].dismiss();
                    }
                    renderDaily(ctx, pageBox);
                    toast(ctx, "已切换到「" + c.name + "」");
                }
            });

            list.addView(row);
        }

        holder[0] = new AlertDialog.Builder(ctx)
                .setTitle("我的配置（点一项启用）")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
    }

    private static void showRenameDialog(final Context ctx, final LinearLayout pageBox,
                                        final List<Node> roots, final SharedPreferences sp,
                                        final SharedPreferences cf, final Cfg c) {
        final EditText et = new EditText(ctx);
        et.setText(c.name);
        et.setSingleLine(true);
        et.setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8));

        new AlertDialog.Builder(ctx)
                .setTitle("给配置改名")
                .setMessage("改的是配置名字，里面的任务清单不会变。")
                .setView(et)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String nn = et.getText().toString().trim();
                        if (nn.length() == 0) {
                            toast(ctx, "名字不能为空");
                            return;
                        }
                        List<Cfg> list = loadMine(cf);
                        Cfg old = findCfg(list, c.name);
                        if (old == null) {
                            return;
                        }
                        if (!nn.equals(c.name) && findCfg(list, nn) != null) {
                            toast(ctx, "已经有同名配置了");
                            return;
                        }
                        old.name = nn;
                        saveMine(cf, list);
                        String sel = cf.getString("mine_sel", "");
                        if (sel.equals(c.name)) {
                            cf.edit().putString("mine_sel", nn).apply();
                        }
                        toast(ctx, "已改名为「" + nn + "」");
                        renderDaily(ctx, pageBox);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void confirmDelete(final Context ctx, final LinearLayout pageBox,
                                      final List<Node> roots, final SharedPreferences sp,
                                      final SharedPreferences cf, final Cfg c) {
        new AlertDialog.Builder(ctx)
                .setTitle("删除配置")
                .setMessage("确定删除「" + c.name + "」吗？删掉后不能恢复。")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        List<Cfg> list = loadMine(cf);
                        List<Cfg> keep = new ArrayList<Cfg>();
                        for (int i = 0; i < list.size(); i++) {
                            if (!list.get(i).name.equals(c.name)) {
                                keep.add(list.get(i));
                            }
                        }
                        saveMine(cf, keep);
                        if (c.name.equals(cf.getString("mine_sel", ""))) {
                            cf.edit().remove("mine_sel").putString("mode", MODE_CUSTOM).apply();
                        }
                        toast(ctx, "已删除「" + c.name + "」");
                        renderDaily(ctx, pageBox);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ======================= 推荐配置 =======================

    private static void showRecDialog(final Context ctx, final LinearLayout pageBox,
                                      final List<Node> roots, final SharedPreferences sp,
                                      final SharedPreferences cf) {
        final List<Cfg> rec = recommend(roots);
        final String cur = cf.getString("rec_sel", rec.get(0).name);

        String[] items = new String[rec.size()];
        int checked = 0;
        for (int i = 0; i < rec.size(); i++) {
            Cfg c = rec.get(i);
            items[i] = c.name + "　（" + c.count() + " 项"
                    + (c.repeat > 0 ? " · " + c.repeat + " 次" : "") + "）";
            if (c.name.equals(cur)) {
                checked = i;
            }
        }

        new AlertDialog.Builder(ctx)
                .setTitle("推荐配置（官方推荐）")
                .setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        Cfg c = rec.get(which);
                        cf.edit().putString("mode", MODE_REC)
                                .putString("rec_sel", c.name).apply();
                        d.dismiss();
                        renderDaily(ctx, pageBox);
                        toast(ctx, "已启用推荐配置「" + c.name + "」");
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    // ======================= 保存配置 =======================

    private static void showSaveDialog(final Context ctx, final LinearLayout pageBox,
                                       final List<Node> roots, final SharedPreferences sp,
                                       final SharedPreferences cf) {
        // 以当前界面上勾着的任务为内容（无论当前是哪种模式）
        final List<String> picked = new ArrayList<String>();
        for (int i = 0; i < roots.size(); i++) {
            collectCheckedLeaves(roots.get(i), picked);
        }
        if (picked.isEmpty()) {
            new AlertDialog.Builder(ctx)
                    .setTitle("保存配置")
                    .setMessage("现在还没有勾选任何任务。\n\n先在上面勾选几项，再来保存。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }

        List<Cfg> exist = loadMine(cf);
        final EditText et = new EditText(ctx);
        et.setText("配置" + (exist.size() + 1));
        et.setSingleLine(true);
        et.setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8));

        new AlertDialog.Builder(ctx)
                .setTitle("保存为我的配置")
                .setMessage("把当前勾选（" + picked.size() + " 项任务）重命名后存入「我的配置」，"
                        + "以后在「我的配置」里一键切换。")
                .setView(et)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String name = et.getText().toString().trim();
                        if (name.length() == 0) {
                            toast(ctx, "名字不能为空");
                            return;
                        }
                        List<Cfg> list = loadMine(cf);
                        Cfg same = findCfg(list, name);
                        if (same != null) {
                            toast(ctx, "已有同名配置，换个名字");
                            return;
                        }
                        Cfg c = new Cfg();
                        c.name = name;
                        c.time = System.currentTimeMillis();
                        c.tasks = join(picked);
                        list.add(c);
                        saveMine(cf, list);
                        cf.edit().putString("mode", MODE_MINE)
                                .putString("mine_sel", name).apply();
                        toast(ctx, "已保存并启用「" + name + "」");
                        renderDaily(ctx, pageBox);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ======================= 任务树渲染 =======================

    /** 网格一行放几个任务 */
    private static final int GRID_COLS = 4;

    /** 一个区（凡间 / 上界）：标题行 + 下面所有子模块 */
    private static View buildSection(Context ctx, final LinearLayout pageBox,
                                     final List<Node> roots, final SharedPreferences sp,
                                     final SharedPreferences cf, final Node section,
                                     int depth, final boolean readOnly) {
        LinearLayout card = card(ctx);
        card.addView(buildRow(ctx, pageBox, roots, sp, cf, section, depth, true, readOnly));
        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        addChildren(ctx, body, pageBox, roots, sp, cf, section, depth + 1, readOnly);
        card.addView(body);
        return card;
    }

    /**
     * 排布一个父项下面的所有子项。
     *
     * 只有「这一层全是叶子」时才铺成每行 4 个的格子；
     * 只要这一层里混着有下级的项，就全部按整行渲染。
     * 否则像「天庭」「刑天战场」这种没有子项的同级项会被塞进
     * 上一项的格子区，看起来像是别人的子项。
     */
    private static void addChildren(Context ctx, LinearLayout body, final LinearLayout pageBox,
                                    final List<Node> roots, final SharedPreferences sp,
                                    final SharedPreferences cf, final Node parent,
                                    int depth, final boolean readOnly) {
        boolean allLeaves = true;
        for (int i = 0; i < parent.children.size(); i++) {
            if (parent.children.get(i).isParent()) {
                allLeaves = false;
                break;
            }
        }

        if (allLeaves) {
            flushGrid(ctx, body, pageBox, roots, sp, cf, parent.children, depth, readOnly);
            return;
        }

        for (int i = 0; i < parent.children.size(); i++) {
            Node c = parent.children.get(i);
            body.addView(buildRow(ctx, pageBox, roots, sp, cf, c, depth, false, readOnly));
            if (c.isParent()) {
                addChildren(ctx, body, pageBox, roots, sp, cf, c, depth + 1, readOnly);
            }
        }
    }

    /** 把攒下的叶子项按每行 4 个铺成格子；不足 4 个补空位，保证列对齐 */
    private static void flushGrid(Context ctx, LinearLayout body, final LinearLayout pageBox,
                                  final List<Node> roots, final SharedPreferences sp,
                                  final SharedPreferences cf, final List<Node> leaves,
                                  int depth, final boolean readOnly) {
        if (leaves.isEmpty()) {
            return;
        }
        for (int start = 0; start < leaves.size(); start += GRID_COLS) {
            LinearLayout line = new LinearLayout(ctx);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(dp(ctx, 4 + depth * 10), dp(ctx, 1), dp(ctx, 4), dp(ctx, 1));

            for (int k = 0; k < GRID_COLS; k++) {
                int idx = start + k;
                if (idx < leaves.size()) {
                    line.addView(buildCell(ctx, pageBox, roots, sp, cf, leaves.get(idx), readOnly));
                } else {
                    View gap = new View(ctx);
                    line.addView(gap, new LinearLayout.LayoutParams(0, 1, 1f));
                }
            }
            body.addView(line);
        }
    }

    /** 网格里的一小格：勾选框 + 名称，宽度四等分，纵向与邻格对齐 */
    private static View buildCell(Context ctx, final LinearLayout pageBox,
                                  final List<Node> roots, final SharedPreferences sp,
                                  final SharedPreferences cf, final Node n,
                                  final boolean readOnly) {
        LinearLayout cell = new LinearLayout(ctx);
        cell.setOrientation(LinearLayout.HORIZONTAL);
        cell.setGravity(Gravity.CENTER_VERTICAL);
        cell.setClickable(true);
        cell.setPadding(dp(ctx, 1), dp(ctx, 3), dp(ctx, 1), dp(ctx, 3));
        cell.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        cell.addView(checkBoxSized(ctx, n.checked, false, 15));

        TextView label = new TextView(ctx);
        label.setText(n.name);
        // 四列格子最窄，字号和间距都要收紧，否则「仙界灵宠」「掌门大厅」
        // 这种四字任务名会被截成「仙界灵…」，看不出是哪一项。
        label.setTextSize(10.5f);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setTextColor(Color.parseColor(n.checked ? COLOR_TEXT : "#FF3A3A3A"));
        label.setPadding(dp(ctx, 3), 0, 0, 0);
        cell.addView(label, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        cell.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (readOnly) {
                    toast(ctx, "当前是已保存的配置，勾选已锁定；点「自定义配置」可自由勾选");
                    return;
                }
                setChecked(n, !n.checked);
                for (int i = 0; i < roots.size(); i++) {
                    normalize(roots.get(i));
                }
                saveCustom(sp, roots);
                renderDaily(ctx, pageBox);
            }
        });
        return cell;
    }

    /** 一行：勾选框 + 名称 */
    private static View buildRow(Context ctx, final LinearLayout pageBox,
                                 final List<Node> roots, final SharedPreferences sp,
                                 final SharedPreferences cf, final Node n, int depth,
                                 final boolean isSectionTitle, final boolean readOnly) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 4 + depth * 12), dp(ctx, 5), dp(ctx, 4), dp(ctx, 5));
        row.setClickable(true);

        if (isSectionTitle) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#FFF1F7F2"));
            bg.setCornerRadius(dp(ctx, 6));
            row.setBackgroundDrawable(bg);
        }

        row.addView(checkBox(ctx, n.checked, n.isParent() && n.anyChecked() && !n.allChecked()));

        TextView label = new TextView(ctx);
        label.setText(n.name);
        label.setTextSize(isSectionTitle ? 15 : (n.isParent() ? 13.5f : 13));
        label.setTextColor(Color.parseColor(n.checked ? COLOR_TEXT : "#FF3A3A3A"));
        label.setTypeface(isSectionTitle || n.isParent()
                ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        label.setPadding(dp(ctx, 8), 0, 0, 0);
        row.addView(label, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (n.isParent()) {
            int c = 0;
            for (int i = 0; i < n.children.size(); i++) {
                if (n.children.get(i).anyChecked()) {
                    c++;
                }
            }
            TextView cnt = new TextView(ctx);
            cnt.setText("(" + c + "/" + n.children.size() + ")");
            cnt.setTextSize(11.5f);
            cnt.setTextColor(Color.parseColor(COLOR_MUTED));
            row.addView(cnt);
        }

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (readOnly) {
                    toast(ctx, "当前是已保存的配置，勾选已锁定；点「自定义配置」可自由勾选");
                    return;
                }
                setChecked(n, !n.checked);
                for (int i = 0; i < roots.size(); i++) {
                    normalize(roots.get(i));
                }
                saveCustom(sp, roots);
                renderDaily(ctx, pageBox);
            }
        });
        return row;
    }

    /** 方框勾选：勾上显示 √，没勾是空白框；半勾用灰色 √ */
    private static TextView checkBox(Context ctx, boolean checked, boolean partial) {
        return checkBoxSized(ctx, checked, partial, 18);
    }

    /** 同上，但可以指定边长（四列格子空间紧，用 15 更合适） */
    private static TextView checkBoxSized(Context ctx, boolean checked, boolean partial, int size) {
        TextView v = new TextView(ctx);
        v.setText(checked ? "√" : "");
        v.setTextSize(size <= 15 ? 11 : 12);
        v.setGravity(Gravity.CENTER);
        v.setTextColor(Color.parseColor(partial ? COLOR_MUTED : COLOR_OK));
        v.setTypeface(Typeface.DEFAULT_BOLD);

        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(Color.parseColor(checked ? "#FFF1F7F2" : "#FFFFFFFF"));
        d.setCornerRadius(dp(ctx, 3));
        int stroke = Math.max(1, dp(ctx, 1));
        d.setStroke(stroke, Color.parseColor(checked ? COLOR_OK : "#FFB8BDC4"));
        v.setBackgroundDrawable(d);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(ctx, size), dp(ctx, size));
        v.setLayoutParams(lp);
        return v;
    }

    private static TextView smallBtn(Context ctx, String text) {
        TextView v = new TextView(ctx);
        v.setText(text);
        v.setTextSize(12);
        v.setTextColor(Color.parseColor("#FF3A3A3A"));
        v.setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 10), dp(ctx, 4));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor(COLOR_SOFT));
        g.setCornerRadius(dp(ctx, 4));
        g.setStroke(Math.max(1, dp(ctx, 1)), Color.parseColor(COLOR_LINE));
        v.setBackgroundDrawable(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(ctx, 6);
        v.setLayoutParams(lp);
        return v;
    }

    private static TextView sortChip(Context ctx, String text, boolean on,
                                     View.OnClickListener l) {
        TextView v = new TextView(ctx);
        v.setText(text);
        v.setTextSize(12);
        v.setTextColor(Color.parseColor(on ? "#FFFFFFFF" : "#FF3A3A3A"));
        v.setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 10), dp(ctx, 4));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor(on ? COLOR_OK : COLOR_SOFT));
        g.setCornerRadius(dp(ctx, 10));
        g.setStroke(Math.max(1, dp(ctx, 1)), Color.parseColor(on ? COLOR_OK : COLOR_LINE));
        v.setBackgroundDrawable(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(ctx, 6);
        v.setLayoutParams(lp);
        v.setOnClickListener(l);
        return v;
    }

    private static void toast(Context ctx, String text) {
        try {
            Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    // ======================= 仙榜 =======================

    /**
     * 仙榜页：按截图做成分组的勾选树。
     *
     * 结构为 限时活动 / 仙榜（主榜·风云·巅峰）/ 释放榜（本服·跨服·争霸）。
     * 勾选结果按「索引路径」保存，例如 1.0.2 表示
     * 第 2 个根的第 1 个子项的第 3 个孩子。
     * 这样「本服」在「主榜」和「释放榜」下面同名也不会互相串。
     */
    public static void renderRank(Context ctx, final LinearLayout box) {
        box.removeAllViews();

        final SharedPreferences sp = ctx.getSharedPreferences(SP_CFG, Context.MODE_PRIVATE);
        final List<Node> roots = rankTree();

        List<String> sel = splitPaths(sp.getString("rank_sel", ""));
        for (int i = 0; i < roots.size(); i++) {
            rankApply(roots.get(i), String.valueOf(i), sel);
            rankFix(roots.get(i));
        }

        box.addView(pageTitle(ctx, "仙榜"));
        box.addView(caption(ctx, "勾选要执行的榜单；勾父项会把下面所有子项一起勾上。选择会自动保存。"));

        final Runnable save = new Runnable() {
            @Override
            public void run() {
                List<String> out = new ArrayList<String>();
                for (int i = 0; i < roots.size(); i++) {
                    rankWalk(roots.get(i), String.valueOf(i), out);
                }
                sp.edit().putString("rank_sel", rankJoin(out)).apply();
                renderRank(ctx, box);
            }
        };

        for (int i = 0; i < roots.size(); i++) {
            Node sec = roots.get(i);
            LinearLayout card = card(ctx);
            card.addView(rankRow(ctx, roots, sec, 0, true, save));
            rankChildren(ctx, card, roots, sec, 1, save);
            box.addView(card);
        }
    }

    /** 仙榜的三个大区，层级与游戏内一致 */
    private static List<Node> rankTree() {
        List<Node> roots = new ArrayList<Node>();

        Node limit = node("限时活动", true);
        rankLeaves(limit, "通天神木（灵力）", "妖兽作乱（幼灵）",
                "秘境寻宝（论道）", "藏经阁（资质）");
        roots.add(limit);

        Node rank = node("仙榜", true);

        Node main = node("主榜", true);
        Node benfu = node("本服", true);
        rankLeaves(benfu, "灵修消耗榜", "灵军榜", "遗迹榜", "灵契消耗榜", "仙力榜",
                "灵宠榜", "论道榜", "幼灵榜", "资质榜", "小世界榜");
        Node kuafu = node("跨服", true);
        rankLeaves(kuafu, "区服仙力榜", "区服灵宠榜", "区服论道榜", "区服幼灵榜", "区服资质榜");
        Node xuantian = node("玄天", true);
        rankLeaves(xuantian, "玄天仙力榜", "玄天灵宠榜", "玄天论道榜", "玄天幼灵榜", "玄天资质榜");
        Node zhengba = node("争霸", true);
        rankLeaves(zhengba, "仙力争霸榜", "灵宠争霸榜", "论道争霸榜", "资质争霸榜");
        main.children.add(benfu);
        main.children.add(kuafu);
        main.children.add(xuantian);
        main.children.add(zhengba);

        Node fengyun = node("风云", true);
        Node hongmeng = node("鸿蒙", true);
        rankLeaves(hongmeng, "仙力风云榜 - 鸿蒙", "灵宠风云榜 - 鸿蒙", "论道风云榜 - 鸿蒙",
                "幼灵风云榜 - 鸿蒙", "资质风云榜 - 鸿蒙");
        Node jiuxiao = node("九霄", true);
        rankLeaves(jiuxiao, "仙力风云榜 - 九霄", "灵宠风云榜 - 九霄", "论道风云榜 - 九霄",
                "幼灵风云榜 - 九霄", "资质风云榜 - 九霄");
        Node wandao = node("万道", true);
        rankLeaves(wandao, "仙力风云榜 - 万道", "灵宠风云榜 - 万道", "论道风云榜 - 万道",
                "幼灵风云榜 - 万道", "资质风云榜 - 万道");
        fengyun.children.add(hongmeng);
        fengyun.children.add(jiuxiao);
        fengyun.children.add(wandao);

        Node dianfeng = node("巅峰", true);
        rankLeaves(dianfeng, "巅峰之战");

        rank.children.add(main);
        rank.children.add(fengyun);
        rank.children.add(dianfeng);
        roots.add(rank);

        Node release = node("释放榜", true);
        Node rBenfu = node("本服", true);
        rankLeaves(rBenfu, "魔族入侵");
        Node rKuafu = node("跨服", true);
        rankLeaves(rKuafu, "魔族争霸");
        Node rZhengba = node("争霸", true);
        rankLeaves(rZhengba, "绝地论道");
        release.children.add(rBenfu);
        release.children.add(rKuafu);
        release.children.add(rZhengba);
        roots.add(release);

        return roots;
    }

    private static void rankLeaves(Node parent, String... names) {
        for (int i = 0; i < names.length; i++) {
            parent.children.add(node(names[i]));
        }
    }

    /** 一组子项：全是叶子就铺成格子，混着父项就整行排（避免父项被挤成别人的子项） */
    private static void rankChildren(Context ctx, LinearLayout body, final List<Node> roots,
                                     final Node parent, int depth, final Runnable save) {
        boolean allLeaves = true;
        for (int i = 0; i < parent.children.size(); i++) {
            if (parent.children.get(i).isParent()) {
                allLeaves = false;
                break;
            }
        }
        if (allLeaves) {
            rankGrid(ctx, body, roots, parent.children, depth, save);
            return;
        }
        for (int i = 0; i < parent.children.size(); i++) {
            Node c = parent.children.get(i);
            body.addView(rankRow(ctx, roots, c, depth, false, save));
            if (c.isParent()) {
                rankChildren(ctx, body, roots, c, depth + 1, save);
            }
        }
    }

    /** 按本组最长的名字决定几列，名字越长放得越少，保证不被截断 */
    private static int rankCols(List<Node> items) {
        int max = 0;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).name.length() > max) {
                max = items.get(i).name.length();
            }
        }
        if (max <= 4) {
            return 4;
        } else if (max <= 8) {
            return 3;
        }
        return 2;
    }

    private static void rankGrid(Context ctx, LinearLayout body, final List<Node> roots,
                                 final List<Node> items, int depth, final Runnable save) {
        if (items.isEmpty()) {
            return;
        }
        int cols = rankCols(items);
        for (int start = 0; start < items.size(); start += cols) {
            LinearLayout line = new LinearLayout(ctx);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(dp(ctx, 4 + depth * 10), dp(ctx, 1), dp(ctx, 4), dp(ctx, 1));
            for (int k = 0; k < cols; k++) {
                int idx = start + k;
                if (idx < items.size()) {
                    line.addView(rankCell(ctx, roots, items.get(idx), save));
                } else {
                    View gap = new View(ctx);
                    line.addView(gap, new LinearLayout.LayoutParams(0, 1, 1f));
                }
            }
            body.addView(line);
        }
    }

    /** 格子里的一项：勾选框 + 名称 */
    private static View rankCell(Context ctx, final List<Node> roots, final Node n,
                                 final Runnable save) {
        LinearLayout cell = new LinearLayout(ctx);
        cell.setOrientation(LinearLayout.HORIZONTAL);
        cell.setGravity(Gravity.CENTER_VERTICAL);
        cell.setClickable(true);
        cell.setPadding(dp(ctx, 1), dp(ctx, 3), dp(ctx, 1), dp(ctx, 3));
        cell.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        cell.addView(checkBoxSized(ctx, n.checked, false, 15));

        TextView label = new TextView(ctx);
        label.setText(n.name);
        label.setTextSize(10.5f);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setTextColor(Color.parseColor(n.checked ? COLOR_TEXT : "#FF3A3A3A"));
        label.setPadding(dp(ctx, 3), 0, 0, 0);
        cell.addView(label, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        cell.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setChecked(n, !n.checked);
                save.run();
            }
        });
        return cell;
    }

    /** 整行的一项：父项或子项，带 (已勾/总数) */
    private static View rankRow(Context ctx, final List<Node> roots, final Node n, int depth,
                                final boolean isSectionTitle, final Runnable save) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 4 + depth * 12), dp(ctx, 5), dp(ctx, 4), dp(ctx, 5));
        row.setClickable(true);

        if (isSectionTitle) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#FFF1F7F2"));
            bg.setCornerRadius(dp(ctx, 6));
            row.setBackgroundDrawable(bg);
        }

        row.addView(checkBox(ctx, n.checked, n.isParent() && n.anyChecked() && !n.allChecked()));

        TextView label = new TextView(ctx);
        label.setText(n.name);
        label.setTextSize(isSectionTitle ? 15 : (n.isParent() ? 13.5f : 13));
        label.setTextColor(Color.parseColor(n.checked ? COLOR_TEXT : "#FF3A3A3A"));
        label.setTypeface(isSectionTitle || n.isParent()
                ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        label.setPadding(dp(ctx, 8), 0, 0, 0);
        row.addView(label, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (n.isParent()) {
            int c = 0;
            for (int i = 0; i < n.children.size(); i++) {
                if (n.children.get(i).anyChecked()) {
                    c++;
                }
            }
            TextView cnt = new TextView(ctx);
            cnt.setText("(" + c + "/" + n.children.size() + ")");
            cnt.setTextSize(11.5f);
            cnt.setTextColor(Color.parseColor(COLOR_MUTED));
            row.addView(cnt);
        }

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setChecked(n, !n.checked);
                save.run();
            }
        });
        return row;
    }

    /** 勾选状态：按索引路径存取，重名也不会串 */
    private static List<String> splitPaths(String raw) {
        List<String> out = new ArrayList<String>();
        if (raw == null || raw.length() == 0) {
            return out;
        }
        String[] parts = raw.split("\\|");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].length() > 0) {
                out.add(parts[i]);
            }
        }
        return out;
    }

    private static String rankJoin(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append("|");
            }
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    private static void rankWalk(Node n, String prefix, List<String> out) {
        if (!n.isParent()) {
            if (n.checked) {
                out.add(prefix);
            }
            return;
        }
        for (int i = 0; i < n.children.size(); i++) {
            rankWalk(n.children.get(i), prefix + "." + i, out);
        }
    }

    private static void rankApply(Node n, String prefix, List<String> sel) {
        if (!n.isParent()) {
            n.checked = sel.contains(prefix);
            return;
        }
        for (int i = 0; i < n.children.size(); i++) {
            rankApply(n.children.get(i), prefix + "." + i, sel);
        }
    }

    /** 父项的勾选状态由子项算出来 */
    private static void rankFix(Node n) {
        if (!n.isParent()) {
            return;
        }
        for (int i = 0; i < n.children.size(); i++) {
            rankFix(n.children.get(i));
        }
        n.checked = n.allChecked();
    }

    // ======================= 活动 =======================

    /** 活动页：目前只有领地征讨，做成和日常页一样的勾选框，后续再往里加 */
    public static void renderActivity(Context ctx, final LinearLayout box) {
        box.removeAllViews();
        final SharedPreferences sp = ctx.getSharedPreferences(SP_CFG, Context.MODE_PRIVATE);

        box.addView(pageTitle(ctx, "活动"));

        LinearLayout card = card(ctx);
        final boolean on = sp.getBoolean("act_lingdi", false);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setPadding(dp(ctx, 4), dp(ctx, 8), dp(ctx, 4), dp(ctx, 8));
        row.addView(checkBox(ctx, on, false));

        TextView label = new TextView(ctx);
        label.setText("领地征讨");
        label.setTextSize(13.5f);
        label.setTextColor(Color.parseColor(on ? COLOR_TEXT : "#FF3A3A3A"));
        label.setPadding(dp(ctx, 8), 0, 0, 0);
        row.addView(label, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sp.edit().putBoolean("act_lingdi", !sp.getBoolean("act_lingdi", false)).apply();
                renderActivity(ctx, box);
            }
        });

        card.addView(row);
        box.addView(card);
    }

    // ======================= 小部件 =======================

    private static TextView pageTitle(Context ctx, String text) {
        TextView v = new TextView(ctx);
        v.setText(text);
        v.setTextSize(17);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(Color.parseColor(COLOR_TEXT));
        v.setPadding(0, dp(ctx, 2), 0, dp(ctx, 2));
        return v;
    }

    private static TextView caption(Context ctx, String text) {
        TextView v = new TextView(ctx);
        v.setText(text);
        v.setTextSize(12);
        v.setTextColor(Color.parseColor(COLOR_MUTED));
        v.setPadding(0, 0, 0, dp(ctx, 8));
        return v;
    }

    private static LinearLayout card(Context ctx) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundDrawable(rounded(ctx, COLOR_CARD, COLOR_LINE));
        box.setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(ctx, 8);
        box.setLayoutParams(lp);
        return box;
    }

    private static TextView rowTitle(Context ctx, String title, String right) {
        TextView v = new TextView(ctx);
        v.setText(title + "  " + right);
        v.setTextSize(14);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(Color.parseColor(COLOR_TEXT));
        return v;
    }

    private static TextView body(Context ctx, String text, boolean dim) {
        TextView v = new TextView(ctx);
        v.setText(text);
        v.setTextSize(12.5f);
        v.setTextColor(Color.parseColor(dim ? COLOR_MUTED : "#FF555555"));
        v.setPadding(0, dp(ctx, 4), 0, 0);
        return v;
    }

    private static TextView hint(Context ctx, String text) {
        TextView v = new TextView(ctx);
        v.setText(text);
        v.setTextSize(12.5f);
        v.setTextColor(Color.parseColor(COLOR_MUTED));
        v.setPadding(dp(ctx, 10), dp(ctx, 10), dp(ctx, 10), dp(ctx, 10));
        v.setBackgroundDrawable(rounded(ctx, COLOR_SOFT, COLOR_LINE));
        return v;
    }

    private static GradientDrawable rounded(Context ctx, String fill, String stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(ctx, 8));
        d.setColor(Color.parseColor(fill));
        d.setStroke(1, Color.parseColor(stroke));
        return d;
    }

    private static int dp(Context ctx, float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
