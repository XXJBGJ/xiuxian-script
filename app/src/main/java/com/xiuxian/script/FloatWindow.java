package com.xiuxian.script;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 悬浮窗（版本 2）：不只是一个开关，而是一块「当前在跑哪一步」的进度面板。
 *
 * 显示内容：
 *   状态     运行中/待机 + 当前游戏名
 *   当前步骤 第 3/12 步 · 登录
 *   当前等待 还需 4.2s / 已用 1.3s（自动倒计时）
 *   当前备注 这一步想干什么
 *   下一步   第 4/12 步 · 点击
 *   下一步   约 0.8s · 备注
 *
 * 交互：
 *   - 启动脚本时自动出现（AutoService.startScript 里调用 show）
 *   - 「收起」变一个小球，点小球再展开，随时能收起来看游戏
 *   - 「×」彻底关掉，主界面「显示悬浮窗」还能再叫回来
 *   - 整块可拖动
 */
public class FloatWindow {

    private final Context ctx;
    private final WindowManager wm;
    private View root;
    private WindowManager.LayoutParams lp;

    private LinearLayout panel;   // 展开后的全部内容（收起时隐藏）

    private TextView tvStatus;
    private TextView tvCurStep;
    private TextView tvCurWait;
    private TextView tvCurNote;
    private TextView tvNextStep;
    private TextView tvNextInfo;

    private TextView ball;        // 收起状态下的小球
    private boolean expanded = true;

    private Handler ticker;
    private Runnable tickTask;

    private static FloatWindow inst;

    /** 全局唯一实例，Activity 重建也不丢状态 */
    public static synchronized FloatWindow get(Context context) {
        if (inst == null) {
            inst = new FloatWindow(context);
        }
        return inst;
    }

    public FloatWindow(Context context) {
        this.ctx = context.getApplicationContext();
        this.wm = (WindowManager) this.ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public boolean isShowing() {
        return root != null;
    }

    public void show() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    show();
                }
            });
            return;
        }
        if (root != null || wm == null) {
            expand();
            return;
        }
        try {
            LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xEE101010);
            bg.setCornerRadius(dp(ctx, 10));
            bg.setStroke(dp(ctx, 1), 0x6600E676);
            box.setBackground(bg);
            int pad = dp(ctx, 8);
            box.setPadding(pad, pad, pad, pad);

            // ---------- 顶部标题行 ----------
            LinearLayout headRow = new LinearLayout(ctx);
            headRow.setOrientation(LinearLayout.HORIZONTAL);
            headRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView title = new TextView(ctx);
            title.setText("脚本进度");
            title.setTextColor(0xFF00E676);
            title.setTextSize(12);
            title.getPaint().setFakeBoldText(true);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            headRow.addView(title, tlp);

            Button btnFold = smallButton("收起", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    collapse();
                }
            });
            Button btnClose = smallButton("×", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    hide();
                }
            });
            headRow.addView(btnFold);
            headRow.addView(btnClose);
            box.addView(headRow);

            // ---------- 收起态的小球 ----------
            ball = new TextView(ctx);
            ball.setText("脚本");
            ball.setTextColor(0xFF00E676);
            ball.setTextSize(11);
            ball.setGravity(Gravity.CENTER);
            GradientDrawable bb = new GradientDrawable();
            bb.setColor(0xEE101010);
            bb.setCornerRadius(dp(ctx, 20));
            bb.setStroke(dp(ctx, 1), 0x6600E676);
            ball.setBackground(bb);
            ball.setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6));
            ball.setVisibility(View.GONE);
            ball.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    expand();
                }
            });
            box.addView(ball);

            // ---------- 展开态内容 ----------
            panel = new LinearLayout(ctx);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(0, dp(ctx, 6), 0, 0);

            tvStatus = infoRow(panel, 0xFFB9F6CA, "待机");
            tvCurStep = infoRow(panel, 0xFFFFD54F, "步骤: --");
            tvCurWait = infoRow(panel, 0xFF8CE0FF, "等待: --");
            tvCurNote = infoRow(panel, 0xFFCCCCCC, "备注: --");
            tvNextStep = infoRow(panel, 0xFFFFAB91, "下一步: --");
            tvNextInfo = infoRow(panel, 0xFFBDBDBD, "下一步: --");

            LinearLayout btnRow = new LinearLayout(ctx);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            btnRow.setPadding(0, dp(ctx, 6), 0, 0);

            Button btnStart = smallButton("启动", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    AutoService s = AutoService.get();
                    if (s == null) {
                        Logger.e("无障碍服务未开启，无法启动脚本");
                        toast("请先在主界面开启无障碍服务");
                        return;
                    }
                    s.startScript();
                    refresh();
                }
            });
            Button btnStop = smallButton("停止", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    AutoService s = AutoService.get();
                    if (s != null) {
                        s.stopScript();
                    }
                    refresh();
                }
            });
            btnRow.addView(btnStart);
            btnRow.addView(btnStop);
            panel.addView(btnRow);

            box.addView(panel);
            box.setMinimumWidth(dp(ctx, 210));

            int type = Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = dp(ctx, 6);
            lp.y = dp(ctx, 90);

            // 拖动：按住整块移动（小球状态下也能拖）
            box.setOnTouchListener(new View.OnTouchListener() {
                private int startX;
                private int startY;
                private float downX;
                private float downY;
                private boolean dragging;

                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    switch (e.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            startX = lp.x;
                            startY = lp.y;
                            downX = e.getRawX();
                            downY = e.getRawY();
                            dragging = false;
                            return true;
                        case MotionEvent.ACTION_MOVE: {
                            int dx = (int) (e.getRawX() - downX);
                            int dy = (int) (e.getRawY() - downY);
                            if (Math.abs(dx) > dp(ctx, 3) || Math.abs(dy) > dp(ctx, 3)) {
                                dragging = true;
                                lp.x = startX + dx;
                                lp.y = startY + dy;
                                try {
                                    wm.updateViewLayout(root, lp);
                                } catch (Throwable ignored) {
                                }
                            }
                            return true;
                        }
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            return dragging;
                    }
                    return false;
                }
            });

            root = box;
            try {
                wm.addView(root, lp);
                Logger.i("悬浮窗已显示，可拖动");
            } catch (Throwable t) {
                root = null;
                Logger.e("悬浮窗添加失败（检查悬浮窗权限）: " + t);
                return;
            }
            expand();
            startTicker();
        } catch (Throwable t) {
            Logger.e("悬浮窗创建异常: " + t);
        }
    }

    private Button smallButton(String text, View.OnClickListener l) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setTextSize(10);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(ctx, 6), dp(ctx, 2), dp(ctx, 6), dp(ctx, 2));
        b.setOnClickListener(l);
        return b;
    }

    private TextView infoRow(LinearLayout parent, int color, String init) {
        TextView tv = new TextView(ctx);
        tv.setTextColor(color);
        tv.setTextSize(10.5f);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setText(init);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(ctx, 1);
        parent.addView(tv, p);
        return tv;
    }

    public void expand() {
        expanded = true;
        if (panel != null) {
            panel.setVisibility(View.VISIBLE);
        }
        if (ball != null) {
            ball.setVisibility(View.GONE);
        }
        refresh();
    }

    public void collapse() {
        expanded = false;
        if (panel != null) {
            panel.setVisibility(View.GONE);
        }
        if (ball != null) {
            ball.setVisibility(View.VISIBLE);
            ball.setText("脚本 " + shortStep());
        }
    }

    private String shortStep() {
        AutoService s = AutoService.get();
        if (s == null) {
            return "服务未开";
        }
        if (!s.isRunning()) {
            return "待机";
        }
        String n = s.pCurName;
        if (n == null || n.isEmpty()) {
            return "运行中";
        }
        return n.length() > 10 ? n.substring(0, 10) : n;
    }

    private void startTicker() {
        stopTicker();
        ticker = new Handler(Looper.getMainLooper());
        tickTask = new Runnable() {
            @Override
            public void run() {
                refresh();
                if (ticker != null) {
                    ticker.postDelayed(this, 400);
                }
            }
        };
        ticker.postDelayed(tickTask, 400);
    }

    private void stopTicker() {
        if (ticker != null && tickTask != null) {
            ticker.removeCallbacks(tickTask);
        }
        ticker = null;
        tickTask = null;
    }

    public void hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    hide();
                }
            });
            return;
        }
        stopTicker();
        if (root == null || wm == null) {
            return;
        }
        try {
            wm.removeView(root);
        } catch (Throwable ignored) {
        }
        root = null;
        panel = null;
        ball = null;
        Logger.i("悬浮窗已关闭（可在主界面重新显示）");
    }

    private static String fmt(double sec) {
        if (sec < 0) {
            sec = 0;
        }
        return String.format(java.util.Locale.US, "%.1f", sec);
    }

    /** 刷新面板上的全部文字（每 400ms 由 ticker 调用一次） */
    public void refresh() {
        if (root == null) {
            return;
        }
        AutoService s = AutoService.get();
        String g = Games.selectedName(ctx);
        if (g == null || g.isEmpty()) {
            g = "未选择游戏";
        }

        if (s == null) {
            setText(tvStatus, "服务未开启 · " + g);
            setText(tvCurStep, "步骤: --");
            setText(tvCurWait, "等待: --");
            setText(tvCurNote, "备注: 请先开启无障碍服务");
            setText(tvNextStep, "下一步: --");
            setText(tvNextInfo, "");
            return;
        }

        if (s.isRunning()) {
            setText(tvStatus, "运行中 · " + g);
        } else {
            setText(tvStatus, "待机 · " + g);
        }

        String cur = s.pCurName;
        setText(tvCurStep, "本步: " + (cur == null || cur.isEmpty() ? "--（尚未开始）" : cur));

        long now = System.currentTimeMillis();
        String wait;
        if (s.pCurEnd > now) {
            wait = "还需 " + fmt((s.pCurEnd - now) / 1000.0) + " s";
        } else if (s.pCurStart > 0 && s.isRunning()) {
            wait = "已用 " + fmt((now - s.pCurStart) / 1000.0) + " s";
        } else {
            wait = "--";
        }
        setText(tvCurWait, "等待: " + wait);

        String note = s.pCurNote;
        setText(tvCurNote, "备注: " + (note == null || note.isEmpty() ? "--" : note));

        String nx = s.pNextName;
        setText(tvNextStep, "下一步: " + (nx == null || nx.isEmpty() ? "--" : nx));

        String nxInfo;
        if (s.pNextMs > 0) {
            nxInfo = "约 " + fmt(s.pNextMs / 1000.0) + " s";
        } else {
            nxInfo = "";
        }
        String nxNote = s.pNextNote;
        if (nxNote != null && !nxNote.isEmpty()) {
            nxInfo = nxInfo.isEmpty() ? nxNote : nxInfo + " · " + nxNote;
        }
        setText(tvNextInfo, "    " + nxInfo);

        if (!expanded && ball != null) {
            ball.setText("脚本 " + shortStep());
        }
    }

    private void setText(TextView tv, String t) {
        if (tv != null && t != null) {
            tv.setText(t);
        }
    }

    /** 兼容老调用点 */
    public void refreshStatus() {
        refresh();
    }

    private void toast(String msg) {
        try {
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }
}
