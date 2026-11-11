package com.xiuxian.script;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 悬浮球：启动/停止脚本的唯一入口。
 * - 单击：未运行则启动脚本（并自动打开运行日志悬浮窗），运行中则停止
 * - 长按：选择从第几步开始（默认 1），并可查看上一步真实耗时
 * - 拖动：不可移除界面；贴左右边缘时自动半隐藏（留一小部分在外）
 * - 不可关闭：没有关闭按钮，只能隐藏日志悬浮窗
 */
public class FloatBall {

    private static final long LONG_PRESS_MS = 500L;
    private static final int BALL_DP = 46;
    private static final int PEEK_PX_RATIO = 45; // 贴边时露出宽度的百分比

    private static FloatBall instance;

    private final Context ctx;
    private final WindowManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final WindowManager.LayoutParams lp;

    private LinearLayout root;
    private TextView tvLabel;
    private boolean showing = false;
    private boolean dockedLeft = true;

    // 触摸状态
    private float downRawX, downRawY;
    private int startX, startY;
    private boolean moved;
    private long downAt;
    private boolean longFired;

    private final Runnable longPress = new Runnable() {
        @Override
        public void run() {
            if (!moved) {
                longFired = true;
                openStepChooser();
            }
        }
    };

    private FloatBall(Context c) {
        this.ctx = c.getApplicationContext();
        this.wm = (WindowManager) this.ctx.getSystemService(Context.WINDOW_SERVICE);
        this.lp = new WindowManager.LayoutParams();
        lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        lp.format = android.graphics.PixelFormat.TRANSLUCENT;
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.width = FloatWindow.dp(this.ctx, BALL_DP + 10);
        lp.height = FloatWindow.dp(this.ctx, BALL_DP + 10);
    }

    public static synchronized FloatBall get(Context c) {
        if (instance == null) {
            instance = new FloatBall(c);
        }
        return instance;
    }

    public boolean isShowing() {
        return showing;
    }

    /** 显示悬浮球（脚本启动时自动调用）。已显示则只刷新外观。 */
    public void show() {
        if (showing && root != null) {
            applyLook();
            return;
        }
        try {
            build();
            if (!showing && root != null) {
                wm.addView(root, lp);
                showing = true;
                snapToEdge(true);
            }
        } catch (Throwable t) {
            Logger.e("悬浮球显示失败: " + t);
        }
    }

    /** 悬浮球常驻，不提供关闭；此方法仅在应用退出时内部使用 */
    public void hideQuietly() {
        try {
            if (root != null && showing) {
                wm.removeView(root);
            }
        } catch (Throwable ignored) {
        }
        showing = false;
    }

    private int[] screen() {
        int[] wh = new int[]{1080, 1920};
        try {
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            wh[0] = dm.widthPixels;
            wh[1] = dm.heightPixels;
        } catch (Throwable ignored) {
        }
        return wh;
    }

    private void build() {
        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);

        tvLabel = new TextView(ctx);
        tvLabel.setTextColor(Color.WHITE);
        tvLabel.setGravity(Gravity.CENTER);
        tvLabel.setTextSize(13f);
        tvLabel.setText("▶");
        root.addView(tvLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        applyLook();
        root.setOnTouchListener(touch);
    }

    private void applyLook() {
        boolean run = false;
        try {
            AutoService s = AutoService.get();
            run = s != null && s.isRunning();
        } catch (Throwable ignored) {
        }
        int bg = run ? 0xFFE53935 : 0xFF2E7D32;
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(bg);
        g.setStroke(FloatWindow.dp(ctx, 2), 0x66FFFFFF);
        root.setBackground(g);
        root.setAlpha(run ? 1f : 0.95f);
        if (tvLabel != null) {
            tvLabel.setText(run ? "■" : "▶");
        }
    }

    /** 贴左/右边缘；semiHide=true 时不完全隐藏，保留一小块可再次拖出 */
    private void snapToEdge(boolean semiHide) {
        int[] wh = screen();
        int w = lp.width;
        int h = lp.height;
        int peek = Math.max(FloatWindow.dp(ctx, 6), w * PEEK_PX_RATIO / 100);
        int centerX = lp.x + w / 2;
        dockedLeft = centerX < wh[0] / 2;
        int targetX;
        if (semiHide) {
            targetX = dockedLeft ? (peek - w) : (wh[0] - peek);
        } else {
            targetX = dockedLeft ? FloatWindow.dp(ctx, 2) : (wh[0] - w - FloatWindow.dp(ctx, 2));
        }
        lp.x = targetX;
        lp.y = clamp(lp.y, FloatWindow.dp(ctx, 20), wh[1] - h - FloatWindow.dp(ctx, 20));
        try {
            wm.updateViewLayout(root, lp);
        } catch (Throwable ignored) {
        }
    }

    private static int clamp(int v, int lo, int hi) {
        if (hi < lo) {
            return lo;
        }
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private final View.OnTouchListener touch = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = e.getRawX();
                    downRawY = e.getRawY();
                    startX = lp.x;
                    startY = lp.y;
                    moved = false;
                    longFired = false;
                    downAt = System.currentTimeMillis();
                    ui.postDelayed(longPress, LONG_PRESS_MS);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int dx = (int) (e.getRawX() - downRawX);
                    int dy = (int) (e.getRawY() - downRawY);
                    if (Math.abs(dx) > FloatWindow.dp(ctx, 4) || Math.abs(dy) > FloatWindow.dp(ctx, 4)) {
                        moved = true;
                        ui.removeCallbacks(longPress);
                    }
                    if (moved) {
                        int[] wh = screen();
                        lp.x = clamp(startX + dx, -lp.width / 2, wh[0] - lp.width / 2);
                        lp.y = clamp(startY + dy, FloatWindow.dp(ctx, 10), wh[1] - lp.height - FloatWindow.dp(ctx, 10));
                        try {
                            wm.updateViewLayout(root, lp);
                        } catch (Throwable ignored) {
                        }
                    }
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    ui.removeCallbacks(longPress);
                    return true;
                case MotionEvent.ACTION_UP:
                    ui.removeCallbacks(longPress);
                    if (moved) {
                        snapToEdge(true); // 贴边半隐藏，不完全消失
                    } else if (!longFired && System.currentTimeMillis() - downAt < LONG_PRESS_MS) {
                        tap();
                    }
                    return true;
                default:
                    return false;
            }
        }
    };

    /** 单击：启动 / 停止 */
    private void tap() {
        try {
            AutoService s = AutoService.get();
            if (s == null) {
                Logger.e("无障碍服务未连接，无法启动脚本");
                return;
            }
            if (s.isRunning()) {
                s.stopScript();
                applyLook();
            } else {
                // 启动脚本时自动打开运行日志悬浮窗 + 悬浮球常驻
                s.startScript();
                FloatWindow.get(ctx).show();
                applyLook();
            }
        } catch (Throwable t) {
            Logger.e("悬浮球启停失败: " + t);
        }
    }

    /** 长按：选择起始步骤 + 查看上一步耗时 */
    private void openStepChooser() {
        try {
            final AutoService s = AutoService.get();
            final String[] titles = s == null ? new String[0] : s.stepTitles();
            final int curStart = s == null ? 1 : Math.max(1, s.getStartStep());

            final LinearLayout panel = new LinearLayout(ctx);
            panel.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xF0202020);
            bg.setCornerRadius(FloatWindow.dp(ctx, 10));
            panel.setBackground(bg);
            int pad = FloatWindow.dp(ctx, 10);
            panel.setPadding(pad, pad, pad, pad);

            TextView title = new TextView(ctx);
            title.setText("从第几步开始（当前默认 " + curStart + "）");
            title.setTextColor(Color.WHITE);
            title.setTextSize(13f);
            title.setPadding(0, 0, 0, FloatWindow.dp(ctx, 6));
            panel.addView(title);

            // 步骤列表（自绘，避免 AlertDialog 在 overlay 里没有主题）
            ScrollView sv = new ScrollView(ctx);
            LinearLayout list = new LinearLayout(ctx);
            list.setOrientation(LinearLayout.VERTICAL);
            sv.addView(list, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, FloatWindow.dp(ctx, 150)));

            final TextView[] holders = new TextView[]{null};
            int total = Math.max(1, titles.length);
            for (int i = 0; i < total; i++) {
                final int stepOne = i + 1;
                TextView row = new TextView(ctx);
                String name = i < titles.length && titles[i] != null ? titles[i] : ("第 " + stepOne + " 步");
                row.setText((stepOne == curStart ? "● " : "○ ") + stepOne + ". " + name);
                row.setTextColor(stepOne == curStart ? 0xFF64B5F6 : 0xFFDDDDDD);
                row.setTextSize(12f);
                row.setPadding(0, FloatWindow.dp(ctx, 5), 0, FloatWindow.dp(ctx, 5));
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (s != null) {
                            s.setStartStep(stepOne);
                        }
                        Logger.i("已设置从第 " + stepOne + " 步开始");
                        if (holders[0] != null) {
                            holders[0].setText("已选择：第 " + stepOne + " 步（点击空白处关闭）");
                        }
                    }
                });
                list.addView(row);
            }
            panel.addView(sv);

            TextView tip = new TextView(ctx);
            tip.setTextColor(0xFFAAAAAA);
            tip.setTextSize(11f);
            tip.setText("提示：默认从 1 开始。下方为最近一步实际耗时");
            panel.addView(tip);

            TextView timing = new TextView(ctx);
            timing.setTextColor(0xFF9CCC65);
            timing.setTextSize(11f);
            String rep = s == null ? "" : s.timingReport();
            String[] lines = rep.split("\n");
            StringBuilder last = new StringBuilder();
            int from = Math.max(0, lines.length - 5);
            for (int i = from; i < lines.length; i++) {
                if (lines[i].trim().length() > 0) {
                    last.append(lines[i].trim()).append('\n');
                }
            }
            timing.setText(last.length() == 0 ? "还没有运行记录" : last.toString().trim());
            panel.addView(timing);

            Button close = new Button(ctx);
            close.setText("关闭");
            close.setTextSize(12f);
            close.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    overlayPanel(false, null);
                }
            });
            panel.addView(close);

            holders[0] = new TextView(ctx);
            holders[0].setVisibility(View.GONE);
            panel.addView(holders[0]);

            overlayPanel(true, panel);
        } catch (Throwable t) {
            Logger.e("选步骤面板失败: " + t);
        }
    }

    // ---- 简易覆盖面板 ----
    private View panelView;
    private WindowManager.LayoutParams panelLp;

    private void overlayPanel(boolean showIt, View panel) {
        try {
            if (!showIt) {
                if (panelView != null) {
                    wm.removeView(panelView);
                }
                panelView = null;
                return;
            }
            if (panelView != null) {
                wm.removeView(panelView);
                panelView = null;
            }
            panelView = panel;
            panelLp = new WindowManager.LayoutParams();
            panelLp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            panelLp.format = android.graphics.PixelFormat.TRANSLUCENT;
            panelLp.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
            panelLp.gravity = Gravity.CENTER;
            int[] wh = screen();
            panelLp.width = Math.min(wh[0] - FloatWindow.dp(ctx, 40), FloatWindow.dp(ctx, 300));
            panelLp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            wm.addView(panelView, panelLp);
        } catch (Throwable t) {
            Logger.e("面板显示失败: " + t);
        }
    }
}
