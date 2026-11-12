package com.xiuxian.script;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 日常 / 仙榜 / 活动 三个页面的内容渲染。
 *
 * 数据来源优先级：
 *   1. assets/pages_data.json（存在就用它，方便以后只改 JSON 不改代码）
 *   2. 代码里内置的默认数据（下面 DEFAULT_* 常量）
 *
 * 全部用代码搭界面，不新增布局文件，避免多文件提交出错。
 */
public class Pages {

    private static final String COLOR_TEXT = "#FF1F1F1F";
    private static final String COLOR_MUTED = "#FF8A8A8A";
    private static final String COLOR_OK = "#FF2E8B57";
    private static final String COLOR_WARN = "#FFC9822F";
    private static final String COLOR_LINE = "#FFE2E2E2";
    private static final String COLOR_CARD = "#FFFFFFFF";
    private static final String COLOR_SOFT = "#FFF7F7F7";

    // ======================= 内置默认数据 =======================

    /** 日常：项目分步规则与闭环进度（与 runner 的分步配置一一对应） */
    private static final String DEFAULT_DAILY = "{\n"
            + "  \"updated\": \"2026-11-12\",\n"
            + "  \"tasks\": [\n"
            + "    {\"id\":\"06\",\"module\":\"凡间\",\"title\":\"06 演武（条件判断版）\",\"status\":\"done\",\"note\":\"22 步条件版已真机跑通，兑换段闭环；斗法段战斗弹窗模板待补\"},\n"
            + "    {\"id\":\"04\",\"module\":\"凡间\",\"title\":\"04 坊市（条件判断版）\",\"status\":\"pending\",\"note\":\"骨架与可达锚点已回填，待真机闭环；聚宝盆/争夺胜负模板待补\"},\n"
            + "    {\"id\":\"05\",\"module\":\"凡间\",\"title\":\"05 行囊（条件判断版）\",\"status\":\"pending\",\"note\":\"未真机闭环，单件出售段留手动\"},\n"
            + "    {\"id\":\"07\",\"module\":\"凡间\",\"title\":\"07 慕仙境（条件判断版）\",\"status\":\"pending\",\"note\":\"待复验二次膜拜/瞻仰刷新分支与获得时间弹窗\"},\n"
            + "    {\"id\":\"08\",\"module\":\"凡间\",\"title\":\"08 宗门（条件判断版）\",\"status\":\"pending\",\"note\":\"3 类未触发界面模板待补：确定/开始练功/关闭传功室\"},\n"
            + "    {\"id\":\"09\",\"module\":\"凡间\",\"title\":\"09 修炼（条件判断版）\",\"status\":\"pending\",\"note\":\"8 类弹窗模板待触发后补；纳气点位顺序未核\"},\n"
            + "    {\"id\":\"10\",\"module\":\"凡间\",\"title\":\"10 吐纳（条件判断版）\",\"status\":\"pending\",\"note\":\"待真机复核闭环\"},\n"
            + "    {\"id\":\"11\",\"module\":\"仙盟\",\"title\":\"11 进入仙盟（条件判断版）\",\"status\":\"pending\",\"note\":\"骨架已落地，模板待真机抓\"},\n"
            + "    {\"id\":\"12\",\"module\":\"仙盟\",\"title\":\"12 建设（条件判断版）\",\"status\":\"pending\",\"note\":\"骨架已落地，模板待真机抓\"},\n"
            + "    {\"id\":\"13\",\"module\":\"仙盟\",\"title\":\"13 排行榜（条件判断版）\",\"status\":\"pending\",\"note\":\"骨架已落地，模板待真机抓\"},\n"
            + "    {\"id\":\"14\",\"module\":\"仙盟\",\"title\":\"14 仙盟事务（条件判断版）\",\"status\":\"pending\",\"note\":\"7 步参数已读全，7 个占位模板待抓\"},\n"
            + "    {\"id\":\"15\",\"module\":\"仙盟\",\"title\":\"15 宴会（条件判断版）\",\"status\":\"pending\",\"note\":\"4 步参数已读全，4 个占位模板待抓\"},\n"
            + "    {\"id\":\"16\",\"module\":\"仙盟\",\"title\":\"16 除魔（条件判断版）\",\"status\":\"pending\",\"note\":\"8 步参数已读全，7 个占位模板待抓\"},\n"
            + "    {\"id\":\"17\",\"module\":\"仙盟\",\"title\":\"17 天宫幻境（条件判断版）\",\"status\":\"pending\",\"note\":\"8 步参数已读全，5 个占位模板待抓；排位在事务与宴会之间\"}\n"
            + "  ]\n"
            + "}";

    /** 仙榜：游戏内各榜单名次（entries 为空表示还没采集） */
    private static final String DEFAULT_RANK = "{\n"
            + "  \"updated\": \"\",\n"
            + "  \"boards\": [\n"
            + "    {\"name\":\"等级榜\",\"metric\":\"修为\",\"entries\":[],\"hint\":\"尚未采集：进入游戏「仙榜」页后补齐角色名与修为\"},\n"
            + "    {\"name\":\"战力榜\",\"metric\":\"战力\",\"entries\":[],\"hint\":\"尚未采集\"},\n"
            + "    {\"name\":\"仙盟榜\",\"metric\":\"仙盟贡献\",\"entries\":[],\"hint\":\"尚未采集：分步「13 排行榜」闭环后可回填\"}\n"
            + "  ]\n"
            + "}";

    /** 活动：按起止日期自动分 进行中 / 未开始 / 已结束 */
    private static final String DEFAULT_ACTIVITY = "{\n"
            + "  \"activities\": [\n"
            + "    {\"name\":\"每日签到\",\"start\":\"2026-11-01\",\"end\":\"2026-12-31\",\"desc\":\"每日登录领取签到奖励。\",\"tasks\":[\"打开签到界面\",\"领取签到奖励\"]},\n"
            + "    {\"name\":\"除魔\",\"start\":\"2026-11-01\",\"end\":\"2026-11-30\",\"desc\":\"对应分步 16 除魔，先完成今日次数再结算。\",\"tasks\":[\"进入除魔界面\",\"按次数挑战\",\"领取结算奖励\"]},\n"
            + "    {\"name\":\"天宫幻境\",\"start\":\"2026-11-01\",\"end\":\"2026-11-30\",\"desc\":\"对应分步 17 天宫幻境，层数推进后领奖。\",\"tasks\":[\"进入天宫幻境\",\"推进层数\"]}\n"
            + "  ]\n"
            + "}";

    // ======================= 对外入口 =======================

    public static void renderDaily(Context ctx, LinearLayout box) {
        box.removeAllViews();
        JSONObject root = loadJson(ctx, "daily", DEFAULT_DAILY);
        if (root == null) {
            box.addView(hint(ctx, "日常任务数据读取失败"));
            return;
        }
        JSONArray tasks = root.optJSONArray("tasks");

        box.addView(pageTitle(ctx, "日常"));
        box.addView(caption(ctx, "修仙分步规则与闭环进度，共 "
                + (tasks == null ? 0 : tasks.length()) + " 条"));

        if (tasks == null || tasks.length() == 0) {
            box.addView(hint(ctx, "还没有日常任务"));
            return;
        }

        int done = 0;
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject t = tasks.optJSONObject(i);
            if (t == null) continue;
            if ("done".equals(t.optString("status"))) done++;
        }
        box.addView(summary(ctx, "已完成 " + done + " / " + tasks.length()
                + "　（" + pct(done, tasks.length()) + "）"));

        String lastModule = "";
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject t = tasks.optJSONObject(i);
            if (t == null) continue;
            String module = t.optString("module", "");
            if (!module.equals(lastModule)) {
                lastModule = module;
                box.addView(moduleHeader(ctx, module.isEmpty() ? "其他" : module));
            }
            box.addView(taskCard(ctx,
                    t.optString("title", "-"),
                    t.optString("note", ""),
                    t.optString("status", "pending")));
        }
    }

    public static void renderRank(Context ctx, LinearLayout box) {
        box.removeAllViews();
        JSONObject root = loadJson(ctx, "rank", DEFAULT_RANK);
        if (root == null) {
            box.addView(hint(ctx, "仙榜数据读取失败"));
            return;
        }
        String updated = root.optString("updated", "");
        box.addView(pageTitle(ctx, "仙榜"));
        box.addView(caption(ctx, updated.isEmpty() ? "尚未采集名次" : "更新于 " + updated));

        JSONArray boards = root.optJSONArray("boards");
        if (boards == null || boards.length() == 0) {
            box.addView(hint(ctx, "还没有榜单定义"));
            return;
        }
        for (int i = 0; i < boards.length(); i++) {
            JSONObject b = boards.optJSONObject(i);
            if (b == null) continue;
            String name = b.optString("name", "榜单");
            String metric = b.optString("metric", "");
            JSONArray entries = b.optJSONArray("entries");

            LinearLayout card = card(ctx);
            card.addView(rowTitle(ctx, name, entries == null ? "0 条" : entries.length() + " 条"));
            if (entries == null || entries.length() == 0) {
                card.addView(hint(ctx, b.optString("hint", "尚未采集到数据")));
            } else {
                card.addView(tableHead(ctx, metric.isEmpty() ? "数值" : metric));
                for (int k = 0; k < entries.length(); k++) {
                    JSONObject e = entries.optJSONObject(k);
                    if (e == null) continue;
                    card.addView(rankRow(ctx, k + 1,
                            e.optString("name", "-"),
                            e.optString("score", "-")));
                }
            }
            box.addView(card);
        }
    }

    public static void renderActivity(Context ctx, LinearLayout box) {
        box.removeAllViews();
        JSONObject root = loadJson(ctx, "activity", DEFAULT_ACTIVITY);
        if (root == null) {
            box.addView(hint(ctx, "活动数据读取失败"));
            return;
        }
        JSONArray acts = root.optJSONArray("activities");
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());

        box.addView(pageTitle(ctx, "活动"));
        box.addView(caption(ctx, "今天 " + today + "　按起止日期自动分组"));

        if (acts == null || acts.length() == 0) {
            box.addView(hint(ctx, "暂无活动"));
            return;
        }

        List<JSONObject> running = new ArrayList<JSONObject>();
        List<JSONObject> upcoming = new ArrayList<JSONObject>();
        List<JSONObject> ended = new ArrayList<JSONObject>();
        for (int i = 0; i < acts.length(); i++) {
            JSONObject a = acts.optJSONObject(i);
            if (a == null) continue;
            String start = a.optString("start", "");
            String end = a.optString("end", "");
            if (!end.isEmpty() && end.compareTo(today) < 0) {
                ended.add(a);
            } else if (!start.isEmpty() && start.compareTo(today) > 0) {
                upcoming.add(a);
            } else {
                running.add(a);
            }
        }

        addGroup(ctx, box, "进行中", running, COLOR_OK);
        addGroup(ctx, box, "未开始", upcoming, COLOR_WARN);
        addGroup(ctx, box, "已结束", ended, COLOR_MUTED);
    }

    private static void addGroup(Context ctx, LinearLayout box, String title,
                                 List<JSONObject> list, String color) {
        if (list.isEmpty()) return;
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, dp(ctx, 10), 0, dp(ctx, 6));
        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Color.parseColor(color));
        head.addView(t);
        TextView c = new TextView(ctx);
        c.setText("　" + list.size() + " 个");
        c.setTextSize(12);
        c.setTextColor(Color.parseColor(COLOR_MUTED));
        head.addView(c);
        box.addView(head);

        for (int i = 0; i < list.size(); i++) {
            box.addView(activityCard(ctx, list.get(i), "已结束".equals(title)));
        }
    }

    private static View activityCard(Context ctx, JSONObject a, boolean dim) {
        LinearLayout card = card(ctx);
        card.addView(rowTitle(ctx, a.optString("name", "未命名活动"),
                a.optString("start", "?") + " ~ " + a.optString("end", "?")));
        String desc = a.optString("desc", "");
        if (!desc.isEmpty()) card.addView(body(ctx, desc, dim));
        JSONArray tasks = a.optJSONArray("tasks");
        if (tasks != null) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < tasks.length(); i++) {
                if (sb.length() > 0) sb.append('\n');
                sb.append("· ").append(tasks.optString(i, ""));
            }
            if (sb.length() > 0) card.addView(body(ctx, sb.toString(), dim));
        }
        return card;
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

    private static TextView moduleHeader(Context ctx, String text) {
        TextView v = new TextView(ctx);
        v.setText(text);
        v.setTextSize(13);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(Color.parseColor(COLOR_MUTED));
        v.setPadding(0, dp(ctx, 8), 0, dp(ctx, 4));
        return v;
    }

    private static LinearLayout card(Context ctx) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundDrawable(rounded(COLOR_CARD, COLOR_LINE));
        box.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(ctx, 8);
        box.setLayoutParams(lp);
        return box;
    }

    private static TextView rowTitle(Context ctx, String title, String right) {
        TextView v = new TextView(ctx);
        v.setText(title + "　" + right);
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

    private static TextView tableHead(Context ctx, String metric) {
        TextView v = new TextView(ctx);
        v.setText("名次　　角色　　　　　　" + metric);
        v.setTextSize(11.5f);
        v.setTextColor(Color.parseColor(COLOR_MUTED));
        v.setPadding(0, dp(ctx, 4), 0, dp(ctx, 2));
        return v;
    }

    private static TextView rankRow(Context ctx, int no, String name, String score) {
        TextView v = new TextView(ctx);
        v.setText(no + "　　" + name + "\n　　　　" + score);
        v.setTextSize(13);
        v.setTextColor(Color.parseColor(no <= 3 ? COLOR_OK : COLOR_TEXT));
        v.setPadding(0, dp(ctx, 3), 0, dp(ctx, 3));
        return v;
    }

    private static View taskCard(Context ctx, String title, String note, String status) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackgroundDrawable(rounded(COLOR_SOFT, COLOR_LINE));
        card.setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(ctx, 6);
        card.setLayoutParams(lp);

        LinearLayout left = new LinearLayout(ctx);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        left.setLayoutParams(llp);

        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextSize(13.5f);
        t.setTextColor(Color.parseColor(COLOR_TEXT));
        left.addView(t);

        if (!note.isEmpty()) {
            TextView n = new TextView(ctx);
            n.setText(note);
            n.setTextSize(11.5f);
            n.setTextColor(Color.parseColor(COLOR_MUTED));
            n.setPadding(0, dp(ctx, 2), 0, 0);
            left.addView(n);
        }
        card.addView(left);

        TextView badge = new TextView(ctx);
        boolean done = "done".equals(status);
        badge.setText(done ? "已完成" : "待闭环");
        badge.setTextSize(11.5f);
        badge.setTextColor(Color.parseColor(done ? COLOR_OK : COLOR_WARN));
        badge.setPadding(dp(ctx, 6), dp(ctx, 1), dp(ctx, 6), dp(ctx, 1));
        badge.setBackgroundDrawable(rounded(done ? "#FFEAF5EE" : "#FFFBF3E6", COLOR_LINE));
        card.addView(badge);

        return card;
    }

    private static TextView hint(Context ctx, String text) {
        TextView v = new TextView(ctx);
        v.setText(text);
        v.setTextSize(12.5f);
        v.setTextColor(Color.parseColor(COLOR_MUTED));
        v.setPadding(dp(ctx, 10), dp(ctx, 12), dp(ctx, 10), dp(ctx, 12));
        v.setBackgroundDrawable(rounded(COLOR_SOFT, COLOR_LINE));
        return v;
    }

    private static GradientDrawable rounded(String fill, String stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(8f);
        d.setColor(Color.parseColor(fill));
        d.setStroke(1, Color.parseColor(stroke));
        return d;
    }

    private static int dp(Context ctx, float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String pct(int a, int b) {
        if (b <= 0) return "0%";
        return (int) (a * 100.0f / b) + "%";
    }

    // ======================= 数据读取 =======================

    /** 优先读 assets/pages_data.json 的对应段，没有就用内置默认值。 */
    private static JSONObject loadJson(Context ctx, String key, String fallback) {
        try {
            InputStream in = ctx.getAssets().open("pages_data.json");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            JSONObject all = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
            JSONObject sub = all.optJSONObject(key);
            if (sub != null) return sub;
        } catch (Throwable ignored) {
            // 没有 assets 文件或解析失败，用内置默认数据
        }
        try {
            return new JSONObject(fallback);
        } catch (Throwable t) {
            return null;
        }
    }
}
