package com.xiuxian.script;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 日常 / 仙榜 / 活动 三个页面的内容渲染。
 *
 * 日常：上下两大区（凡间 / 上界），每个模块前一个勾选框，
 *       勾父项会把下面所有子项一起勾上，勾子项会把父项带亮；
 *       勾选结果存在本机，下次进来还是上次的选择。
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

    /** 日常勾选状态的存储名 */
    private static final String SP_DAILY = "daily_sel";

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

    /** 给每个节点算出唯一路径，作为勾选状态的存储键 */
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

    /** 从本机读回上次的勾选状态 */
    private static void loadState(SharedPreferences sp, List<Node> roots) {
        List<Node> all = new ArrayList<Node>();
        for (int i = 0; i < roots.size(); i++) {
            collect(roots.get(i), all);
        }
        for (int i = 0; i < all.size(); i++) {
            Node n = all.get(i);
            n.checked = sp.getBoolean(n.path, false);
        }
        // 父项没有显式存过状态时，按子项推一遍
        for (int i = 0; i < roots.size(); i++) {
            normalize(roots.get(i));
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

    private static void saveState(SharedPreferences sp, List<Node> roots) {
        List<Node> all = new ArrayList<Node>();
        for (int i = 0; i < roots.size(); i++) {
            collect(roots.get(i), all);
        }
        SharedPreferences.Editor e = sp.edit();
        for (int i = 0; i < all.size(); i++) {
            e.putBoolean(all.get(i).path, all.get(i).checked);
        }
        e.apply();
    }

    /** 勾一个节点：自己 + 所有子项一起变；再往上把父项状态带对 */
    private static void setChecked(List<Node> roots, Node target, boolean checked) {
        applyDown(target, checked);
        for (int i = 0; i < roots.size(); i++) {
            normalize(roots.get(i));
        }
    }

    private static void applyDown(Node n, boolean checked) {
        n.checked = checked;
        for (int i = 0; i < n.children.size(); i++) {
            applyDown(n.children.get(i), checked);
        }
    }

    public static void renderDaily(Context ctx, LinearLayout box) {
        box.removeAllViews();
        final List<Node> roots = buildDailyTree();
        for (int i = 0; i < roots.size(); i++) {
            assignPath(roots.get(i), "");
        }
        final SharedPreferences sp = ctx.getSharedPreferences(SP_DAILY, Context.MODE_PRIVATE);
        loadState(sp, roots);

        box.addView(pageTitle(ctx, "日常"));
        box.addView(caption(ctx, "勾选要执行的任务；勾父项会把下面所有子项一起勾上。选择会自动保存。"));

        int total = 0;
        int sel = 0;
        List<Node> all = new ArrayList<Node>();
        for (int i = 0; i < roots.size(); i++) {
            collect(roots.get(i), all);
        }
        for (int i = 0; i < all.size(); i++) {
            total++;
            if (all.get(i).checked) {
                sel++;
            }
        }
        box.addView(summary(ctx, "已勾选 " + sel + " / " + total + " 项任务"));

        for (int i = 0; i < roots.size(); i++) {
            box.addView(buildSection(ctx, box, sp, roots, roots.get(i), 0));
        }

        box.addView(hint(ctx, "说明：勾选只记录要跑哪些任务，具体每一步的点位与等待时间按任务逐条补齐。"));
    }

    /** 一个区（凡间 / 上界）：标题行 + 下面所有子模块 */
    private static View buildSection(Context ctx, final LinearLayout pageBox,
                                     final SharedPreferences sp, final List<Node> roots,
                                     final Node section, int depth) {
        LinearLayout card = card(ctx);
        card.addView(buildRow(ctx, pageBox, sp, roots, section, depth, true));
        for (int i = 0; i < section.children.size(); i++) {
            card.addView(buildNodeView(ctx, pageBox, sp, roots, section.children.get(i), depth + 1));
        }
        return card;
    }

    /** 递归渲染一个节点：自己一行，有子项就往里缩进 */
    private static View buildNodeView(Context ctx, final LinearLayout pageBox,
                                      final SharedPreferences sp, final List<Node> roots,
                                      final Node n, int depth) {
        if (!n.isParent()) {
            return buildRow(ctx, pageBox, sp, roots, n, depth, false);
        }
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(buildRow(ctx, pageBox, sp, roots, n, depth, false));
        for (int i = 0; i < n.children.size(); i++) {
            box.addView(buildNodeView(ctx, pageBox, sp, roots, n.children.get(i), depth + 1));
        }
        return box;
    }

    /** 一行：勾选框 + 名称 */
    private static View buildRow(Context ctx, final LinearLayout pageBox,
                                 final SharedPreferences sp, final List<Node> roots,
                                 final Node n, int depth, final boolean isSectionTitle) {
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
                boolean to = !n.checked;
                setChecked(roots, n, to);
                saveState(sp, roots);
                renderDaily(ctx, pageBox);
            }
        });
        return row;
    }

    /** 方框勾选：勾上显示 √，没勾是空白框；半勾用灰色 √ */
    private static TextView checkBox(Context ctx, boolean checked, boolean partial) {
        TextView v = new TextView(ctx);
        v.setText(checked ? "√" : "");
        v.setTextSize(12);
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

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(ctx, 18), dp(ctx, 18));
        v.setLayoutParams(lp);
        return v;
    }

    // ======================= 仙榜 =======================

    public static void renderRank(Context ctx, LinearLayout box) {
        box.removeAllViews();

        box.addView(pageTitle(ctx, "仙榜"));
        box.addView(caption(ctx, "按从上到下三个区：限时活动 → 主榜 → 释放榜"));

        box.addView(rankSection(ctx, "限时活动",
                "名称、起止时间、当前名次与奖励稍后更新"));
        box.addView(rankSection(ctx, "主榜",
                "榜单名称与名次稍后更新"));
        box.addView(rankSection(ctx, "释放榜",
                "释放榜名次稍后更新"));
    }

    /** 仙榜的一个区：标题 + 数据表（暂时空，等具体内容） */
    private static View rankSection(Context ctx, String title, String hintText) {
        LinearLayout card = card(ctx);
        card.addView(rowTitle(ctx, title, "待补充"));

        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setPadding(0, dp(ctx, 6), 0, dp(ctx, 2));
        head.addView(cell(ctx, "名次", 0.18f, true));
        head.addView(cell(ctx, "角色", 0.42f, true));
        head.addView(cell(ctx, "数值", 0.4f, true));
        card.addView(head);

        for (int i = 1; i <= 3; i++) {
            LinearLayout r = new LinearLayout(ctx);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setPadding(0, dp(ctx, 3), 0, dp(ctx, 3));
            r.addView(cell(ctx, String.valueOf(i), 0.18f, false));
            r.addView(cell(ctx, "—", 0.42f, false));
            r.addView(cell(ctx, "—", 0.4f, false));
            card.addView(r);
        }
        card.addView(hint(ctx, hintText));
        return card;
    }

    private static TextView cell(Context ctx, String text, float weight, boolean head) {
        TextView v = new TextView(ctx);
        v.setText(text);
        v.setTextSize(head ? 11.5f : 12.5f);
        v.setTextColor(Color.parseColor(head ? COLOR_MUTED : COLOR_TEXT));
        if (head) {
            v.setTypeface(Typeface.DEFAULT_BOLD);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
        v.setLayoutParams(lp);
        return v;
    }

    // ======================= 活动 =======================

    public static void renderActivity(Context ctx, LinearLayout box) {
        box.removeAllViews();

        box.addView(pageTitle(ctx, "活动"));
        box.addView(caption(ctx, "目前开放 1 个活动"));

        LinearLayout card = card(ctx);
        card.addView(rowTitle(ctx, "领地征讨", "开放中"));
        card.addView(body(ctx, "玩法：进入领地征讨界面，按次数挑战，结束后领取结算奖励。", false));
        card.addView(body(ctx, "· 进入领地征讨\n· 按次数发起挑战\n· 领取结算奖励", false));
        box.addView(card);

        box.addView(hint(ctx, "更多活动开放后在这里补充。"));
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

    private static TextView summary(Context ctx, String text) {
        TextView v = new TextView(ctx);
        v.setText(text);
        v.setTextSize(13);
        v.setTextColor(Color.parseColor(COLOR_OK));
        v.setPadding(0, dp(ctx, 4), 0, dp(ctx, 6));
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
