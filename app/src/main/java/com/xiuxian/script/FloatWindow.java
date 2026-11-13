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
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * 日志浮窗。
 *
 * 功能：
 * 1. 显示脚本进度，显示内容可在「调整日志浮窗」里逐项勾选，字号/边框/透明度可调；
 * 2. 可拖动，且任何时候都不会被拖出屏幕；
 * 3. 修正上下拖动方向反转（底部对齐时 y 轴与手指方向相反，需取反）；
 * 4. 自带「启动 / 暂停·继续 / 停止」三个按钮；
 * 5. 单击打开「调整日志浮窗」设置，长按可选择从哪个任务开始。
 *
 * 重要修正（构建48）：
 * 旧版用 AlertDialog + getApplicationContext() 弹面板，Android 8 以上会抛
 * BadTokenException（token null is not for an application）直接崩掉整个进程，
 * 表现出来就是「一点悬浮窗，脚本就停了」。
 * 现在面板与长按菜单全部改成自建的悬浮层窗口（TYPE_APPLICATION_OVERLAY），
 * 不再依赖 Dialog 的 token，点多少次都不会崩。
 */
public class FloatWindow {

    private static FloatWindow inst;

    private final Context ctx;
    private final WindowManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private View root;
    private TextView tvTitle;
    private TextView tvBody;
    private LinearLayout titleBar;
    private LinearLayout bodyBox;
    private LinearLayout ctrlBar;
    private Button btnStart;
    private Button btnPause;
    private Button btnStop;

    private boolean showing = false;
    private boolean collapsed = false;

    // 【构建 117】锁定浮窗尺寸：停止后正文变短也不许缩小，保证和启动时一样大
    private int lockW = 0;
    private int lockH = 0;

    /** 【构建 128】浮窗固定宽度（dp）：足够放下四行信息，不再随文字长短伸缩 */
    public static final int WIN_W_DP = 210;
    /** 【构建 128】右侧留给悬浮球的宽度（dp）：球嵌在窗框内部右侧 */
    public static final int BALL_SLOT_DP = 44;

    private WindowManager.LayoutParams lp;
    private FloatConfig cfg;

    /** 自建的浮动面板（设置 / 长按菜单），不使用 Dialog，避免崩溃 */
    private View panelView;
    private WindowManager.LayoutParams panelLp;

    /** 拖动 / 点击 / 长按 判定 */
    private float downRawX;
    private float downRawY;
    private int startX;
    private int startY;
    private long downTime = 0L;
    private boolean dragging = false;
    private static final long LONG_PRESS_MS = 550L;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            if (showing && !collapsed) {
                ui.postDelayed(this, 400);
            }
        }
    };

    private FloatWindow(Context c) {
        this.ctx = c.getApplicationContext();
        this.wm = (WindowManager) this.ctx.getSystemService(Context.WINDOW_SERVICE);
        this.cfg = FloatConfig.load(this.ctx);
    }

    public static synchronized FloatWindow get(Context c) {
        if (inst == null) {
            inst = new FloatWindow(c);
        }
        return inst;
    }

    public boolean isShowing() {
        return showing;
    }

    public static int dp(Context c, float v) {
        if (c == null) {
            return (int) v;
        }
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    private int dpf(float v) {
        return dp(ctx, v);
    }

    /** 屏幕真实尺寸（含状态栏），拖动边界用 */
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

    // ==================== 显示 / 隐藏 ====================

    public void show() {
        if (showing && root != null) {
            refresh();
            return;
        }
        try {
            cfg = FloatConfig.load(ctx);
            build();
            if (!showing && root != null) {
                wm.addView(root, lp);
                showing = true;
            }
            ui.removeCallbacks(ticker);
            ui.post(ticker);
        } catch (Throwable t) {
            Logger.e("悬浮窗显示失败: " + t);
        }
    }

    public void hide() {
        closePanel();
        try {
            ui.removeCallbacks(ticker);
            if (root != null && showing) {
                wm.removeView(root);
            }
        } catch (Throwable ignored) {
        }
        showing = false;
    }

    public void expand() {
        collapsed = false;
        if (bodyBox != null) {
            bodyBox.setVisibility(View.VISIBLE);
        }
        if (ctrlBar != null) {
            ctrlBar.setVisibility(View.VISIBLE);
        }
        clampAndApply();
        refresh();
        ui.removeCallbacks(ticker);
        ui.post(ticker);
    }

    public void collapse() {
        collapsed = true;
        if (bodyBox != null) {
            bodyBox.setVisibility(View.GONE);
        }
        if (ctrlBar != null) {
            ctrlBar.setVisibility(View.GONE);
        }
        clampAndApply();
    }

    // ==================== 构建浮窗 ====================

    private void build() {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);

        // 只保留运行日志：标题栏（修仙脚本/收起/关闭）与启动·暂停·停止控制栏
        // 已按要求移出到悬浮球，这里不再创建。

        // ---- 正文 ----
        bodyBox = new LinearLayout(ctx);
        bodyBox.setOrientation(LinearLayout.VERTICAL);
        tvBody = new TextView(ctx);
        tvBody.setTextColor(0xFFFFFFFF);
        tvBody.setTextSize(cfg.fontSize);
        tvBody.setPadding(dpf(6), dpf(4), dpf(4), dpf(4));
        bodyBox.addView(tvBody);
        // 【构建 128】右侧留出球位，让悬浮球显示在窗框内部而不是贴在窗外
        bodyBox.setPadding(0, 0, dpf(BALL_SLOT_DP), 0);
        box.addView(bodyBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        applyLook(box);

        // ---- 拖动 / 单击 / 长按 ----
        View.OnTouchListener touch = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                try {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downRawX = e.getRawX();
                            downRawY = e.getRawY();
                            startX = lp.x;
                            startY = lp.y;
                            downTime = System.currentTimeMillis();
                            dragging = false;
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            float dx = e.getRawX() - downRawX;
                            float dy = e.getRawY() - downRawY;
                            if (Math.abs(dx) > dpf(3) || Math.abs(dy) > dpf(3)) {
                                dragging = true;
                            }
                            if (dragging && lp != null) {
                                // 顶部对齐后，位移方向与手指完全一致
                                lp.x = startX + (int) dx;
                                lp.y = startY + (int) dy;
                                clampAndApply();
                            }
                            return true;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            long held = System.currentTimeMillis() - downTime;
                            boolean moved = dragging;
                            dragging = false;
                            if (!moved) {
                                if (held >= LONG_PRESS_MS) {
                                    // 长按日志浮窗 = 隐藏（悬浮窗可关闭，悬浮球常驻）
                                    hide();
                                } else {
                                    openSettings();
                                }
                            }
                            return true;
                        default:
                            return false;
                    }
                } catch (Throwable t) {
                    Logger.e("浮窗触摸处理异常: " + t);
                    return false;
                }
            }
        };
        // 日志框整体可拖动；单击打开「调整日志浮窗」，长按隐藏日志浮窗
        box.setOnTouchListener(touch);
        tvBody.setOnTouchListener(touch);
        bodyBox.setOnTouchListener(touch);

        // ---- 窗口参数：底部左对齐 ----
        // 【构建 128】宽度固定：以前 WRAP_CONTENT 会随文字长短伸缩，
        // 停止后文字变短窗口就缩成一小条，看起来就是「悬浮窗太小、看不全」。
        lp = new WindowManager.LayoutParams(
                dpf(WIN_W_DP),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);
        // 改成顶部对齐：y 越大越往下，手指往下拖窗口就往下走，
        // 不再需要用「底部对齐 + y 取反」这种容易搞反的写法。
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dpf(6);
        lp.y = dpf(30);

        root = box;

        box.post(new Runnable() {
            @Override
            public void run() {
                if (!placed) {
                    placed = true;
                    placeDefault();
                }
                clampAndApply();
                refresh();
            }
        });
    }

    /** 浮窗里的「启动」：先确认游戏已装，再拉起脚本 */
    private void onStartClick() {
        AutoService s = AutoService.get();
        if (s == null) {
            Logger.e("无障碍服务未连接，请先在主界面开启无障碍");
            expand();
            return;
        }
        if (s.isRunning()) {
            Logger.w("脚本已在运行中");
            return;
        }
        int gi = Games.selectedIndex(ctx);
        if (!Games.isInstalled(ctx, gi)) {
            Logger.e("选中的游戏未安装: " + Games.pkg(gi));
            return;
        }
        s.startScript();
        refresh();
    }

    private Button smallBtn(String text) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(9f);
        b.setTextColor(0xFFFFFFFF);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setPadding(dpf(3), 0, dpf(3), 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dpf(20)));
        return b;
    }

    private Button ctrlBtn(String text) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(Math.max(7f, cfg.fontSize - 2f));
        b.setTextColor(0xFFFFFFFF);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        return b;
    }

    /** 背景、圆角、边框、透明度都按设置项来 */
    private void applyLook(View v) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        int a = cfg.bgAlpha();
        bg.setColor(Color.argb(a, 0x12, 0x12, 0x12));
        int r = dpf(Math.max(2, cfg.borderSize));
        bg.setCornerRadius(r);
        int stroke = Math.max(1, cfg.borderSize / 2);
        bg.setStroke(dpf(stroke), Color.argb(Math.min(0xFF, a + 0x40), 0xFF, 0xFF, 0xFF));
        v.setBackgroundDrawable(bg);
        if (tvBody != null) {
            tvBody.setTextSize(cfg.fontSize);
        }
    }

    /** 首次布局完成后摆到默认位置（顶部居中偏左） */
    private boolean placed = false;

    /** 位置钳制：横向不出屏，纵向允许越过屏幕顶部 */
    private void clampAndApply() {
        if (root == null || lp == null || !showing) {
            return;
        }
        try {
            int[] wh = screen();
            int w = root.getWidth();
            int h = root.getHeight();
            if (w <= 0) {
                w = dpf(130);
            }
            if (h <= 0) {
                h = dpf(60);
            }
            // 与悬浮球同标准：四个方向都允许移出屏幕，
            // 只在屏幕内保留一小块（20dp）露出来，随时能再拖回去。
            int peekX = Math.min(dpf(20), Math.max(dpf(10), w / 3));
            int peekY = Math.min(dpf(20), Math.max(dpf(10), h / 3));
            int minX = peekX - w;
            int maxX = wh[0] - peekX;
            int minY = peekY - h;
            int maxY = wh[1] - peekY;
            if (lp.x < minX) {
                lp.x = minX;
            }
            if (lp.x > maxX) {
                lp.x = maxX;
            }
            if (lp.y < minY) {
                lp.y = minY;
            }
            if (lp.y > maxY) {
                lp.y = maxY;
            }
            wm.updateViewLayout(root, lp);
            // 【构建 113】窗被拖动/收放时，悬浮球跟着贴到窗右侧，保持整体
            if (!syncingFromBall) {
                FloatBall.syncBesideWindow(lp.x, lp.y, w, h);
            }
        } catch (Throwable ignored) {
        }
    }

    // ==================== 内容刷新 ====================

    public void refresh() {
        if (!showing || root == null) {
            return;
        }
        try {
            AutoService s = AutoService.get();
            if (tvBody != null) {
                tvBody.setText(s == null ? "服务未连接" : buildText(s, cfg));
            }
            applyLook(root);
            clampAndApply();
            lockSize();
            // 首帧布局还没完成时量不到真实尺寸，布局完成后再钉一次
            root.post(new Runnable() {
                @Override
                public void run() {
                    lockSize();
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /**
     * 【构建 117】把浮窗尺寸钉住。
     *
     * 之前窗口是 WRAP_CONTENT，文字一短（例如停止后只剩一行状态）窗口就跟着收缩，
     * 看起来就是「一停止脚本悬浮窗就变小」。这里记住显示以来出现过的最大尺寸，
     * 之后只许长不许缩，停止后仍与启动时一样大。
     */
    private void lockSize() {
        if (root == null || lp == null || !showing || collapsed) {
            return;
        }
        try {
            // 【构建 128】宽度恒定为 WIN_W_DP，高度只记最大值，保证停止后也完整显示四行
            lockW = dpf(WIN_W_DP);
            int h = root.getHeight();
            if (h > lockH) {
                lockH = h;
            }
            if (lockH <= 0) {
                return;
            }
            if (lp.width != lockW || lp.height != lockH) {
                lp.width = lockW;
                lp.height = lockH;
                wm.updateViewLayout(root, lp);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 默认位置：屏幕顶部，水平居中后略微偏左，避免和右上角悬浮球重叠 */
    /** 【构建 113】合并组的默认落点：整组靠顶部、靠左；窗在左、球紧贴右侧 */
    public static int[] defaultGroupRect(Context c, int screenW, int screenH) {
        int w = dp(c, WIN_W_DP);
        int h = dp(c, 96);
        int left = dp(c, 6);
        int top = Math.max(dp(c, 26), (int) (screenH * 0.05f));
        if (top > Math.max(0, screenH - h)) {
            top = Math.max(0, screenH - h);
        }
        return new int[]{left, top, w, h};
    }

    private static boolean syncingFromBall = false;

    /**
     * 【构建 113】运行日志窗与悬浮球合并成一个整体：窗永远贴在球左侧、纵向对齐。
     * 两个窗口仍然各自独立（点击不会互相穿透），只是位置联动。
     */
    public static void syncBesideBall(int bx, int by, int bw, int bh) {
        FloatWindow f = inst;
        if (f == null || f.root == null || f.lp == null || !f.showing || syncingFromBall) {
            return;
        }
        syncingFromBall = true;
        try {
            int[] wh = f.screen();
            int w = f.root.getWidth() > 0 ? f.root.getWidth() : f.dpf(130);
            int h = f.root.getHeight() > 0 ? f.root.getHeight() : f.dpf(60);
            int bh2 = bh > 0 ? bh : f.dpf(40);
            // 【构建 128】球嵌在窗框内部右侧：窗口左边 = 球左边 - 窗宽 + 球位宽
            f.lp.x = bx - w + f.dpf(BALL_SLOT_DP) - Math.max(0, (f.dpf(BALL_SLOT_DP) - bw) / 2);
            f.lp.y = by + (bh2 - h) / 2;
            int peekX = Math.min(f.dpf(20), Math.max(f.dpf(10), w / 3));
            int peekY = Math.min(f.dpf(20), Math.max(f.dpf(10), h / 3));
            if (f.lp.x < peekX - w) {
                f.lp.x = peekX - w;
            }
            if (f.lp.x > wh[0] - peekX) {
                f.lp.x = wh[0] - peekX;
            }
            if (f.lp.y < peekY - h) {
                f.lp.y = peekY - h;
            }
            if (f.lp.y > wh[1] - peekY) {
                f.lp.y = wh[1] - peekY;
            }
            f.wm.updateViewLayout(f.root, f.lp);
        } catch (Throwable ignored) {
        } finally {
            syncingFromBall = false;
        }
    }

    private void placeDefault() {
        if (root == null || lp == null) {
            return;
        }
        try {
            int[] wh = screen();
            int[] g = defaultGroupRect(ctx, wh[0], wh[1]);
            lp.x = g[0];
            lp.y = g[1];
        } catch (Throwable ignored) {
        }
    }

    /** 按勾选项拼出显示内容；小于 0.5 秒的时间不显示 */
    private String buildText(AutoService s, FloatConfig c) {
        StringBuilder out = new StringBuilder();
        String nl = String.valueOf((char) 10);

        if (c.showLoop && s.pLoopTotal > 0) {
            out.append("循环 ").append(Math.max(1, s.pLoopIdx)).append("/").append(s.pLoopTotal).append(nl);
        }
        if (c.showLoopGap && s.pLoopGapMs >= 500) {
            out.append("轮间隔 ").append(fmtSec(s.pLoopGapMs)).append("s").append(nl);
        }

        // 【构建 128】固定四行，且每行标签独立、内容各占一行：
        //   当前步骤 / 当前备注 / 当前等待 / 下个步骤 / 下个备注
        // 以前把备注和等待时间挤在同一行，窄浮窗里被截断，看起来就是「显示不全」。
        // 现在无论运行还是停止，这四类信息都单独成行完整显示。
        String curName = s.pCurName == null ? "" : s.pCurName.trim();
        String curNote = s.pCurNote == null ? "" : s.pCurNote.trim();
        String nextName = s.pNextName == null ? "" : s.pNextName.trim();
        String nextNote = s.pNextNote == null ? "" : s.pNextNote.trim();

        if (curName.length() == 0) {
            curName = s.isPaused() ? "已暂停" : (AutoService.isRunningAny() ? "运行中" : "已停止");
        }

        // 第 1 行：当前步骤
        out.append("当前: ").append(curName).append(nl);
        // 第 2 行：当前步骤备注
        out.append("备注: ").append(curNote.length() == 0 ? "--" : curNote).append(nl);
        // 第 3 行：当前等待时间（停止后保留最后一次耗时，不清零成空白）
        String wait;
        if (s.pCurStart > 0) {
            long used = s.pCurElapsedMs > 0 ? s.pCurElapsedMs
                    : (System.currentTimeMillis() - s.pCurStart);
            wait = fmtSec(used) + "s";
        } else if (s.pCurElapsedMs > 0) {
            wait = fmtSec(s.pCurElapsedMs) + "s";
        } else if (s.pNextMs > 0) {
            wait = fmtSec(s.pNextMs) + "s";
        } else {
            wait = "--";
        }
        out.append("等待: ").append(wait).append(nl);
        // 第 4 行：下个步骤 + 其备注
        StringBuilder next = new StringBuilder();
        next.append(nextName.length() == 0 ? "--" : nextName);
        if (nextNote.length() > 0) {
            next.append(" / ").append(nextNote);
        }
        out.append("下步: ").append(next).append(nl);

        // 可选的附加信息放在四行之后，不影响主体四行
        if (c.showCurTimes && s.pStepTimes > 0) {
            out.append("次数: x").append(s.pStepTimes);
            if (s.pStepGapMs >= 500) {
                out.append(" / ").append(fmtSec(s.pStepGapMs)).append("s");
            }
            if (s.pPhase != null && s.pPhase.length() > 0) {
                out.append(" | ").append(s.pPhase);
            }
            out.append(nl);
        } else if (c.showCurStep && s.pPhase != null && s.pPhase.length() > 0) {
            out.append(s.pPhase).append(nl);
        }

        return out.toString().trim();
    }

    private String fmtSec(long ms) {
        double sec = ms / 1000.0;
        if (Math.abs(sec - Math.rint(sec)) < 0.05) {
            return String.valueOf((long) Math.rint(sec));
        }
        return String.format(java.util.Locale.US, "%.1f", sec);
    }

    // ==================== 自建浮动面板（不用 Dialog，杜绝崩溃） ====================

    private void closePanel() {
        try {
            if (panelView != null) {
                wm.removeView(panelView);
            }
        } catch (Throwable ignored) {
        }
        panelView = null;
        panelLp = null;
    }

    /**
     * 把内容包装成一层全屏半透明遮罩 + 居中的卡片，加进 WindowManager。
     * 用 TYPE_APPLICATION_OVERLAY，所以不需要 Activity 的 token。
     */
    private void showPanel(View content, int widthDp) {
        closePanel();
        FrameLayout wrap = new FrameLayout(ctx);
        wrap.setBackgroundColor(0xB3000000);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                dpf(widthDp), FrameLayout.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.CENTER;
        wrap.addView(content, clp);

        GradientDrawable card = new GradientDrawable();
        card.setColor(0xFFFFFFFF);
        card.setCornerRadius(dpf(10));
        content.setBackgroundDrawable(card);
        content.setPadding(dpf(12), dpf(12), dpf(12), dpf(10));

        panelLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        panelLp.gravity = Gravity.CENTER;

        wm.addView(wrap, panelLp);
        panelView = wrap;
    }

    // ==================== 单击：调整日志浮窗 ====================

    public void openSettings() {
        try {
            showSettingsPanel();
        } catch (Throwable t) {
            Logger.e("打开设置面板失败: " + t);
            closePanel();
        }
    }

    private void showSettingsPanel() {
        final FloatConfig c = FloatConfig.load(ctx);

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(ctx);
        title.setText("调整日志浮窗");
        title.setTextSize(19f);
        title.setTextColor(0xFF1F2328);
        title.setPadding(0, 0, 0, dpf(8));
        box.addView(title);

        TextView tip = new TextView(ctx);
        tip.setText("日志内容（以下各时间小于0.5秒均不显示）");
        tip.setTextSize(12f);
        tip.setTextColor(0xFF8A9099);
        tip.setPadding(0, 0, 0, dpf(4));
        box.addView(tip);

        final TextView preview = new TextView(ctx);
        preview.setTextSize(11f);
        preview.setTextColor(0xFF2B2B2B);
        preview.setPadding(dpf(8), dpf(6), dpf(8), dpf(6));
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(0xFF404040);
        pbg.setCornerRadius(dpf(4));
        preview.setBackgroundDrawable(pbg);
        preview.setTextColor(0xFFFFFFFF);

        final Runnable updatePreview = new Runnable() {
            @Override
            public void run() {
                preview.setText(sample(c));
            }
        };

        box.addView(makeCb("循环次数", c.showLoop, new BoolSet() {
            @Override
            public void set(boolean v) {
                c.showLoop = v;
            }
        }, c, updatePreview));
        box.addView(makeCb("循环的间隔时间", c.showLoopGap, new BoolSet() {
            @Override
            public void set(boolean v) {
                c.showLoopGap = v;
            }
        }, c, updatePreview));
        box.addView(makeCb("当前步骤", c.showCurStep, new BoolSet() {
            @Override
            public void set(boolean v) {
                c.showCurStep = v;
            }
        }, c, updatePreview));
        box.addView(makeCb("当前步骤的备注", c.showCurNote, new BoolSet() {
            @Override
            public void set(boolean v) {
                c.showCurNote = v;
            }
        }, c, updatePreview));
        box.addView(makeCb("当前步骤的等待时间", c.showCurWait, new BoolSet() {
            @Override
            public void set(boolean v) {
                c.showCurWait = v;
            }
        }, c, updatePreview));
        box.addView(makeCb("当前步骤内的执行次数和间隔时间", c.showCurTimes, new BoolSet() {
            @Override
            public void set(boolean v) {
                c.showCurTimes = v;
            }
        }, c, updatePreview));
        box.addView(makeCb("下个步骤", c.showNextStep, new BoolSet() {
            @Override
            public void set(boolean v) {
                c.showNextStep = v;
            }
        }, c, updatePreview));
        box.addView(makeCb("下个步骤的备注", c.showNextNote, new BoolSet() {
            @Override
            public void set(boolean v) {
                c.showNextNote = v;
            }
        }, c, updatePreview));

        box.addView(sliderRow("字体大小", 8, 20, c.fontSize, new IntSet() {
            @Override
            public void set(int v) {
                c.fontSize = v;
            }
        }, c, updatePreview));
        box.addView(sliderRow("边框大小", 0, 16, c.borderSize, new IntSet() {
            @Override
            public void set(int v) {
                c.borderSize = v;
            }
        }, c, updatePreview));
        box.addView(sliderRow("浮窗透明度", 0, 20, c.alpha, new IntSet() {
            @Override
            public void set(int v) {
                c.alpha = v;
            }
        }, c, updatePreview));

        TextView pv = new TextView(ctx);
        pv.setText("日志预览效果");
        pv.setTextSize(12f);
        pv.setTextColor(0xFF8A9099);
        pv.setPadding(0, dpf(8), 0, dpf(4));
        box.addView(pv);
        box.addView(preview, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 底部两个按钮
        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(0, dpf(12), 0, 0);

        Button bHide = panelBtn("隐藏日志并关闭");
        bHide.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    c.save(ctx);
                    cfg = c;
                    closePanel();
                    hide();
                } catch (Throwable t) {
                    Logger.e("隐藏失败: " + t);
                }
            }
        });
        btns.addView(bHide, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button bClose = panelBtn("关闭");
        bClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    c.save(ctx);
                    cfg = c;
                    closePanel();
                    refresh();
                } catch (Throwable t) {
                    Logger.e("关闭面板失败: " + t);
                }
            }
        });
        btns.addView(bClose, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(btns);

        updatePreview.run();

        ScrollView sv = new ScrollView(ctx);
        sv.addView(box);
        showPanel(sv, 300);
    }

    private Button panelBtn(String text) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13f);
        return b;
    }

    private interface BoolSet {
        void set(boolean v);
    }

    private interface IntSet {
        void set(int v);
    }

    private View makeCb(String label, boolean checked, final BoolSet setter, final FloatConfig c, final Runnable onChanged) {
        CheckBox cb = new CheckBox(ctx);
        cb.setText(label);
        cb.setTextSize(14f);
        cb.setTextColor(0xFF1F2328);
        cb.setChecked(checked);
        cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean v) {
                try {
                    setter.set(v);
                    c.save(ctx);
                    cfg = c;
                    onChanged.run();
                    refresh();
                } catch (Throwable t) {
                    Logger.e("保存显示项失败: " + t);
                }
            }
        });
        return cb;
    }

    /** 一行滑条：名称+当前值，下方 SeekBar */
    private View sliderRow(String label, final int min, final int max, int cur, final IntSet setter,
                           final FloatConfig c, final Runnable onChanged) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dpf(8), 0, 0);

        final TextView tv = new TextView(ctx);
        tv.setText(label + "：" + cur);
        tv.setTextSize(13f);
        tv.setTextColor(0xFF5A6472);
        box.addView(tv);

        SeekBar sb = new SeekBar(ctx);
        sb.setMax(max - min);
        sb.setProgress(Math.max(0, Math.min(max - min, cur - min)));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                try {
                    int v = min + p;
                    tv.setText(label + "：" + v);
                    setter.set(v);
                    c.save(ctx);
                    cfg = c;
                    onChanged.run();
                    if (root != null) {
                        applyLook(root);
                    }
                    refresh();
                } catch (Throwable t) {
                    Logger.e("调整滑条失败: " + t);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        box.addView(sb);
        return box;
    }

    /** 预览文本：按当前勾选拼一组示例 */
    private String sample(FloatConfig c) {
        StringBuilder out = new StringBuilder();
        String nl = String.valueOf((char) 10);
        if (c.showLoop) {
            out.append("循环 1/3").append(nl);
        }
        if (c.showLoopGap) {
            out.append("轮间隔 2s").append(nl);
        }
        StringBuilder cur = new StringBuilder();
        if (c.showCurStep) {
            cur.append("01");
        }
        if (c.showCurNote) {
            if (cur.length() > 0) {
                cur.append(':');
            }
            cur.append("备注");
        }
        if (c.showCurWait) {
            cur.append("(6s)");
        }
        if (c.showCurTimes) {
            cur.append(" x3/2s");
        }
        if (cur.length() > 0) {
            out.append("当前:").append(cur).append(nl);
        }
        StringBuilder next = new StringBuilder();
        if (c.showNextStep) {
            next.append("滑动02");
        }
        if (c.showNextNote) {
            if (next.length() > 0) {
                next.append('/');
            }
            next.append("备注");
        }
        if (next.length() > 0) {
            out.append("下步:").append(next);
        }
        return out.toString().trim();
    }

    // ==================== 长按：选择从哪个任务开始 ====================

    private void showStartStepChooser() {
        try {
            showChooserPanel();
        } catch (Throwable t) {
            Logger.e("打开起始任务菜单失败: " + t);
            closePanel();
        }
    }

    private void showChooserPanel() {
        final AutoService s = AutoService.get();
        if (s == null) {
            Logger.e("无障碍服务未连接");
            return;
        }
        // 【构建 117】只列日常页已勾选的任务（按固定 1..42 顺序），
        // 没勾的不出现，启动游戏/输入账号这类默认项也不再列出。
        final String[] titles = s.dailyStepTitles();
        if (titles.length == 0) {
            Logger.e("日常页还没有勾选任务，勾选后再长按浮窗");
            return;
        }

        final int cur = Math.max(1, s.getDailyStartIndex());

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(ctx);
        title.setText("从第几项开始（当前 " + cur + "）");
        title.setTextSize(18f);
        title.setTextColor(0xFF1F2328);
        title.setPadding(0, 0, 0, dpf(6));
        box.addView(title);

        TextView tip = new TextView(ctx);
        tip.setText("点一下即可选定并关闭；下次启动按此起点执行，之前已做的会跳过");
        tip.setTextSize(12f);
        tip.setTextColor(0xFF8A9099);
        tip.setPadding(0, 0, 0, dpf(6));
        box.addView(tip);

        // 第一项：从第 1 项开始
        box.addView(choiceRow("从第 1 项开始（默认）", cur == 1, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    s.setDailyStartIndex(1);
                    closePanel();
                } catch (Throwable t) {
                    Logger.e("设置起始任务失败: " + t);
                }
            }
        }));

        for (int i = 0; i < titles.length; i++) {
            final int oneBased = i + 1;
            String name = titles[i] != null && titles[i].length() > 0 ? titles[i] : ("第 " + oneBased + " 项");
            box.addView(choiceRow(oneBased + ". " + name, cur == oneBased, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        s.setDailyStartIndex(oneBased);
                        Logger.i("已设置从第 " + oneBased + " 项开始");
                        closePanel();
                    } catch (Throwable t) {
                        Logger.e("设置起始任务失败: " + t);
                    }
                }
            }));
        }

        Button cancel = panelBtn("取消");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closePanel();
            }
        });
        box.addView(cancel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        ScrollView sv = new ScrollView(ctx);
        sv.addView(box);
        showPanel(sv, 300);
    }

    /** 一行可点的选项：前面用小圆点表示是否选中 */
    private View choiceRow(String text, boolean selected, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dpf(6), dpf(9), dpf(6), dpf(9));
        row.setOnClickListener(click);

        TextView dot = new TextView(ctx);
        dot.setText(selected ? "●" : "○");
        dot.setTextSize(14f);
        dot.setTextColor(selected ? 0xFFE91E63 : 0xFFB0B6BE);
        dot.setPadding(0, 0, dpf(8), 0);
        row.addView(dot);

        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(14f);
        tv.setTextColor(selected ? 0xFFE91E63 : 0xFF1F2328);
        row.addView(tv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        GradientDrawable line = new GradientDrawable();
        line.setColor(0xFFF2F3F5);
        row.setBackgroundDrawable(line);
        return row;
    }
}
