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
 * 悬浮窗（版本 3）：紧凑型小面板，尽量不挡游戏画面。
 *
 * 相比版本 2（210dp 宽、6 行、字号 10.5~12sp）：
 *   - 宽度固定 130dp，字号 7.5~8.5sp，整块面积约为原来的 35%；
 *   - 信息合并成 4 行，长文字自动省略号，不会撑宽；
 *   - 背景更透明（CC 透明度），压住游戏画面时仍能看见下面；
 *   - 默认宽度固定，不会因为文案长短忽大忽小；
 *   - 保留「收起成小球 / ×关闭 / 可拖动 / 启动停止」全部能力。
 *
 * 显示内容（4 行）：
 *   运行中 · 天途
 *   3/3 登录 · 还需 9.8s
 *   备注 识别账号框/密码框/登录按钮
 *   下一步 4/12 点击 · 0.8s
 */
public class FloatWindow {

    /** 面板宽度（dp）：固定值，避免文案不同导致大小跳变 */
    private static final int W_DP = 130;

    private final Context ctx;
    private final WindowManager wm;
    private View root;
    private WindowManager.LayoutParams lp;

    private LinearLayout panel;   // 展开后的全部内容（收起时隐藏）

    private TextView tvStatus;
    private TextView tvCurStep;   // 本步 + 倒计时合并
    private TextView tvCurNote;   // 备注
    private TextView tvNextStep;  // 下一步 + 耗时合并

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
            bg.setColor(0xCC0A0A0A);              // 更透明，少挡画面
            bg.setCornerRadius(dp(ctx, 7));
            bg.setStroke(dp(ctx, 1), 0x5500E676);
            box.setBackground(bg);
            int pad = dp(ctx, 5);
            box.setPadding(pad, dp(ctx, 3), pad, dp(ctx, 4));

            // ---------- 顶部标题行：进度 + 收起 + 关闭 ----------
            LinearLayout headRow = new LinearLayout(ctx);
            headRow.setOrientation(LinearLayout.HORIZONTAL);
            headRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView title = new TextView(ctx);
            title.setText("进度");
            title.setTextColor(0xFF00E676);
            title.setTextSize(9);
            title.getPaint().setFakeBoldText(true);
            title.setSingleLine(true);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            headRow.addView(title, tlp);

            Button btnFold = smallButton("—", new View.OnClickListener() {
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
            ball.setTextSize(9);
            ball.setGravity(Gravity.CENTER);
            GradientDrawable bb = new GradientDrawable();
            bb.setColor(0xCC0A0A0A);
            bb.setCornerRadius(dp(ctx, 14));
            bb.setStroke(dp(ctx, 1), 0x5500E676);
            ball.setBackground(bb);
            ball.setPadding(dp(ctx, 7), dp(ctx, 3), dp(ctx, 7), dp(ctx, 3));
            ball.setVisibility(View.GONE);
            ball.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    expand();
                }
            });
            box.addView(ball);

            // ---------- 展开态内容（4 行） ----------
            panel = new LinearLayout(ctx);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(0, dp(ctx, 2), 0, 0);

            tvStatus = infoRow(panel, 0xFFB9F6CA, "待机");
            tvCurStep = infoRow(panel, 0xFFFFD54F, "--");
            tvCurNote = infoRow(panel, 0xFF9E9E9E, "");
            tvNextStep = infoRow(panel, 0xFF80D8FF, "");

            LinearLayout btnRow = new LinearLayout(ctx);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            btnRow.setPadding(0, dp(ctx, 3), 0, 0);

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

            int type = Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            lp = new WindowManager.LayoutParams(
                    dp(ctx, W_DP),                       // 固定窄宽，不再随文案变化
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            // 关键：非触摸模态。缺这一条，悬浮窗会把落在它范围内
                            // （甚至整屏）的触摸都吃掉，游戏那边收不到点击，
                            // 表现就是"脚本点了但没反应"，同时用户也觉得被挡住。
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = dp(ctx, 6);
            lp.y = dp(ctx, 110);

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
                Logger.i("悬浮窗已显示（紧凑版 " + W_DP + "dp），可拖动");
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
        b.setTextSize(8);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(ctx, 4), 0, dp(ctx, 4), 0);
        b.setBackgroundColor(0x00000000);   // 去掉默认灰底，视觉更轻
        b.setTextColor(0xFF9E9E9E);
        b.setOnClickListener(l);
        return b;
    }

    private TextView infoRow(LinearLayout parent, int color, String init) {
        TextView tv = new TextView(ctx);
        tv.setTextColor(color);
        tv.setTextSize(8f);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setText(init);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
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
        return n.length() > 12 ? n.substring(0, 12) : n;
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
            setText(tvStatus, "服务未开 · " + g);
            setText(tvCurStep, "--");
            setText(tvCurNote, "请先开无障碍");
            setText(tvNextStep, "");
            return;
        }

        setText(tvStatus, (s.isRunning() ? "运行中 · " : "待机 · ") + g);

        // 第 2 行：本步 + 倒计时（合并，省一行）
        String cur = s.pCurName;
        String curTxt = (cur == null || cur.isEmpty()) ? "--" : cur;
        long now = System.currentTimeMillis();
        String wait = "";
        if (s.pCurEnd > now) {
            wait = " · " + fmt((s.pCurEnd - now) / 1000.0) + "s";
        } else if (s.pCurStart > 0 && s.isRunning()) {
            wait = " · 已 " + fmt((now - s.pCurStart) / 1000.0) + "s";
        }
        setText(tvCurStep, curTxt + wait);

        // 第 3 行：备注（过长截断，保证面板不被撑宽）
        String note = s.pCurNote;
        if (note != null && note.length() > 14) {
            note = note.substring(0, 14) + "…";
        }
        setText(tvCurNote, (note == null || note.isEmpty()) ? "" : note);

        // 第 4 行：下一步 + 预计耗时
        String nx = s.pNextName;
        String nxTxt = (nx == null || nx.isEmpty()) ? "" : "下 " + nx;
        if (s.pNextMs > 0) {
            nxTxt = nxTxt + " · 约" + fmt(s.pNextMs / 1000.0) + "s";
        }
        setText(tvNextStep, nxTxt);

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
