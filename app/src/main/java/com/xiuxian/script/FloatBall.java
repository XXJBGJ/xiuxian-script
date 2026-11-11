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
import android.widget.ImageView;
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
    private static final int BALL_DP = 30; // 【构建 66】悬浮球缩小
    private static final int PEEK_PX_RATIO = 45; // 贴边时露出宽度的百分比

    private static FloatBall instance;

    private final Context ctx;
    private final WindowManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final WindowManager.LayoutParams lp;

    private LinearLayout root;
    private TextView tvLabel;
    private ImageView ivIcon;

    /** 【构建 66】悬浮球图标：用户上传头像，内嵌为 base64，避免依赖 res 文件 */
    private static final String BALL_PNG_B64 = "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";
    private static android.graphics.Bitmap ballBmp;
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

        // 【构建 66】悬浮球改用头像图标，圆形裁剪
        ivIcon = new ImageView(ctx);
        ivIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(ivIcon, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        try {
            root.setClipToOutline(true);
        } catch (Throwable ignored) {
        }
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
        // 运行中：红色描边；未运行：淡白描边（图标本身不变，避免遮挡头像）
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0x00000000);
        g.setStroke(FloatWindow.dp(ctx, run ? 3 : 2), run ? 0xFFE53935 : 0x99FFFFFF);
        root.setBackground(g);
        root.setAlpha(run ? 1f : 0.92f);
        if (ivIcon != null && ivIcon.getDrawable() == null) {
            ivIcon.setImageBitmap(ballBitmap());
        }
    }

    /** 解码内嵌头像图标 */
    private static android.graphics.Bitmap ballBitmap() {
        if (ballBmp == null) {
            try {
                byte[] raw = android.util.Base64.decode(BALL_PNG_B64, android.util.Base64.DEFAULT);
                ballBmp = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.length);
            } catch (Throwable ignored) {
            }
        }
        return ballBmp;
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
