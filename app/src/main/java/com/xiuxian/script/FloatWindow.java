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
import android.widget.LinearLayout;
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

    private WindowManager.LayoutParams lp;
    private FloatConfig cfg;

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

    // ==================== 构建界面 ====================

    private void build() {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);

        // ---- 标题栏：拖动区 + 收起 / 关闭 ----
        titleBar = new LinearLayout(ctx);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);

        tvTitle = new TextView(ctx);
        tvTitle.setText("修仙脚本");
        tvTitle.setTextColor(0xFFFFFFFF);
        tvTitle.setTextSize(Math.max(7f, cfg.fontSize - 1f));
        titleBar.addView(tvTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button btnFold = smallBtn("—");
        btnFold.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (collapsed) {
                    expand();
                } else {
                    collapse();
                }
            }
        });
        titleBar.addView(btnFold);

        Button btnClose = smallBtn("×");
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hide();
            }
        });
        titleBar.addView(btnClose);

        box.addView(titleBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // ---- 控制栏：启动 / 暂停·继续 / 停止 ----
        ctrlBar = new LinearLayout(ctx);
        ctrlBar.setOrientation(LinearLayout.HORIZONTAL);

        btnStart = ctrlBtn("启动");
        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AutoService s = AutoService.get();
                if (s == null) {
                    Logger.e("无障碍服务未连接，请先在主界面开启");
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
        });
        ctrlBar.addView(btnStart, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        btnPause = ctrlBtn("暂停");
        btnPause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AutoService s = AutoService.get();
                if (s == null) {
                    Logger.e("无障碍服务未连接");
                    return;
                }
                if (s.isPaused()) {
                    s.resumeScript();
                } else {
                    s.pauseScript();
                }
                refresh();
            }
        });
        ctrlBar.addView(btnPause, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        btnStop = ctrlBtn("停止");
        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AutoService s = AutoService.get();
                if (s == null) {
                    Logger.e("无障碍服务未连接");
                    return;
                }
                s.stopScript();
                refresh();
            }
        });
        ctrlBar.addView(btnStop, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        box.addView(ctrlBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // ---- 正文 ----
        bodyBox = new LinearLayout(ctx);
        bodyBox.setOrientation(LinearLayout.VERTICAL);
        tvBody = new TextView(ctx);
        tvBody.setTextColor(0xFFFFFFFF);
        tvBody.setTextSize(cfg.fontSize);
        tvBody.setPadding(dpf(4), dpf(2), dpf(4), dpf(2));
        bodyBox.addView(tvBody);
        box.addView(bodyBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        applyLook(box);

        // ---- 拖动 / 单击 / 长按 ----
        View.OnTouchListener touch = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
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
                            // 关键修正：底部对齐时 y 轴与手指方向相反，必须取反
                            lp.x = startX + (int) dx;
                            lp.y = startY - (int) dy;
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
                                // 长按：选择从哪个任务开始
                                showStartStepChooser();
                            } else {
                                // 单击：打开调整日志浮窗
                                openSettings();
                            }
                        }
                        return true;
                    default:
                        return false;
                }
            }
        };
        titleBar.setOnTouchListener(touch);
        tvBody.setOnTouchListener(touch);

        // ---- 窗口参数：底部左对齐 ----
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.LEFT;
        lp.x = dpf(8);
        lp.y = dpf(24);

        root = box;

        box.post(new Runnable() {
            @Override
            public void run() {
                clampAndApply();
                refresh();
            }
        });
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

    /** 位置钳制：任何方向都不允许把浮窗推出屏幕 */
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
            int maxX = Math.max(0, wh[0] - w);
            int maxY = Math.max(0, wh[1] - h);
            if (lp.x < 0) {
                lp.x = 0;
            }
            if (lp.x > maxX) {
                lp.x = maxX;
            }
            if (lp.y < 0) {
                lp.y = 0;
            }
            if (lp.y > maxY) {
                lp.y = maxY;
            }
            wm.updateViewLayout(root, lp);
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
            if (s != null) {
                boolean run = s.isRunning();
                if (btnStart != null) {
                    btnStart.setText("启动");
                    btnStart.setEnabled(!run);
                    btnStart.setAlpha(run ? 0.4f : 1f);
                }
                if (btnPause != null) {
                    btnPause.setText(s.isPaused() ? "继续" : "暂停");
                    btnPause.setEnabled(run);
                    btnPause.setAlpha(run ? 1f : 0.4f);
                }
                if (btnStop != null) {
                    btnStop.setEnabled(run);
                    btnStop.setAlpha(run ? 1f : 0.4f);
                }
            }
            if (tvBody != null) {
                tvBody.setText(s == null ? "服务未连接" : buildText(s, cfg));
            }
            applyLook(root);
            clampAndApply();
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

        StringBuilder cur = new StringBuilder();
        if (c.showCurStep && s.pCurName != null && s.pCurName.length() > 0) {
            cur.append(s.pCurName);
        }
        if (c.showCurNote && s.pCurNote != null && s.pCurNote.length() > 0) {
            if (cur.length() > 0) {
                cur.append(':');
            }
            cur.append(s.pCurNote);
        }
        if (c.showCurWait && s.pCurEnd > 0) {
            long left = s.pCurEnd - System.currentTimeMillis();
            if (left >= 500) {
                cur.append('(').append(fmtSec(left)).append("s)");
            }
        }
        if (c.showCurTimes && s.pStepTimes > 0) {
            cur.append(" x").append(s.pStepTimes);
            if (s.pStepGapMs >= 500) {
                cur.append('/').append(fmtSec(s.pStepGapMs)).append("s");
            }
        }
        if (cur.length() > 0) {
            out.append("当前:").append(cur).append(nl);
        }

        StringBuilder next = new StringBuilder();
        if (c.showNextStep && s.pNextName != null && s.pNextName.length() > 0) {
            next.append(s.pNextName);
        }
        if (c.showNextNote && s.pNextNote != null && s.pNextNote.length() > 0) {
            if (next.length() > 0) {
                next.append('/');
            }
            next.append(s.pNextNote);
        }
        if (next.length() > 0) {
            out.append("下步:").append(next);
        }

        String t = out.toString().trim();
        if (t.length() == 0) {
            t = s.isPaused() ? "已暂停" : (s.isRunning() ? "运行中" : "等待开始");
        }
        return t;
    }

    private String fmtSec(long ms) {
        double sec = ms / 1000.0;
        if (Math.abs(sec - Math.rint(sec)) < 0.05) {
            return String.valueOf((long) Math.rint(sec));
        }
        return String.format(java.util.Locale.US, "%.1f", sec);
    }

    // ==================== 长按：选择从哪个任务开始 ====================

    private void showStartStepChooser() {
        final AutoService s = AutoService.get();
        if (s == null) {
            Logger.e("无障碍服务未连接");
            return;
        }
        final String[] titles = s.stepTitles();
        if (titles.length == 0) {
            Logger.e("当前流程没有可选的步骤");
            return;
        }
        // 「默认从最初开始」放第一项
        final String[] items = new String[titles.length + 1];
        items[0] = "默认：从最开始执行";
        for (int i = 0; i < titles.length; i++) {
            items[i + 1] = titles[i];
        }
        final int cur = s.getStartStep();
        final int[] choice = new int[]{cur <= 0 ? 0 : cur};

        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(ctx)
                .setTitle("长按：选择从哪个任务开始")
                .setSingleChoiceItems(items, choice[0], new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        choice[0] = which;
                        if (which == 0) {
                            s.setStartStep(1);
                        } else {
                            s.setStartStep(which);
                        }
                        d.dismiss();
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
    }

    // ==================== 单击：调整日志浮窗 ====================

    public void openSettings() {
        final FloatConfig c = FloatConfig.load(ctx);

        LinearLayout rootBox = new LinearLayout(ctx);
        rootBox.setOrientation(LinearLayout.VERTICAL);
        int pad = dpf(14);
        rootBox.setPadding(pad, pad, pad, pad);

        TextView tip = new TextView(ctx);
        tip.setText("日志内容（以下各时间小于0.5秒均不显示）");
        tip.setTextSize(12f);
        tip.setTextColor(0xFF8A9099);
        rootBox.addView(tip);

        final TextView preview = new TextView(ctx);
        preview.setTextSize(11f);
        preview.setTextColor(0xFF333333);
        preview.setPadding(dpf(8), dpf(6), dpf(8), dpf(6));
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(0xFFF0F1F3);
        pbg.setCornerRadius(dpf(6));
        preview.setBackgroundDrawable(pbg);

        final Runnable updatePreview = new Runnable() {
            @Override
            public void run() {
                preview.setText(sample(c));
            }
        };

        LinearLayout cbBox = new LinearLayout(ctx);
        cbBox.setOrientation(LinearLayout.VERTICAL);
        cbBox.addView(makeCb("循环次数", c.showLoop, new BoolSet() {
            @Override
            public void set(boolean v) {
                c.showLoop = v;
            }
        }, c, updatePreview));
        cbBox.addView(makeCb("循环的间隔时间", c.showLoopGap, new BoolSet() {
            @Override
            public void set(boolean v) {
                c.showLoopGap = v;
            }
        }, c, updatePreview));
        cbBox.addView(makeCb("当前步骤", c.showCurStep, new BoolSet() {
            @Override
            public void set(boolean v) {
                c.showCurStep = v;
            }
        }, c, updatePreview));
        cbBox.addView(makeCb("当前步骤的备注", c.showCurNote, new BoolSet() {
            @Override
            public void set(boolean v) {
                c.showCurNote = v;
            }
        }, c, updatePreview));
        cbBox.addView(makeCb("当前步骤的等待时间", c.showCurWait, new BoolSet() {
            @Override
            public void set(boolean v) {
                c.showCurWait = v;
            }
        }, c, updatePreview));
        cbBox.addView(makeCb("当前步骤内的执行次数和间隔时间", c.showCurTimes, new BoolSet() {
            @Override
            public void set(boolean v) {
                c.showCurTimes = v;
            }
        }, c, updatePreview));
        cbBox.addView(makeCb("下个步骤", c.showNextStep, new BoolSet() {
            @Override
            public void set(boolean v) {
                c.showNextStep = v;
            }
        }, c, updatePreview));
        cbBox.addView(makeCb("下个步骤的备注", c.showNextNote, new BoolSet() {
            @Override
            public void set(boolean v) {
                c.showNextNote = v;
            }
        }, c, updatePreview));
        rootBox.addView(cbBox);

        rootBox.addView(sliderRow("字体大小", 8, 20, c.fontSize, new IntSet() {
            @Override
            public void set(int v) {
                c.fontSize = v;
            }
        }, c, updatePreview));
        rootBox.addView(sliderRow("边框大小", 0, 16, c.borderSize, new IntSet() {
            @Override
            public void set(int v) {
                c.borderSize = v;
            }
        }, c, updatePreview));
        rootBox.addView(sliderRow("浮窗透明度", 0, 20, c.alpha, new IntSet() {
            @Override
            public void set(int v) {
                c.alpha = v;
            }
        }, c, updatePreview));

        TextView pv = new TextView(ctx);
        pv.setText("日志预览效果");
        pv.setTextSize(12f);
        pv.setTextColor(0xFF8A9099);
        pv.setPadding(0, dpf(10), 0, dpf(4));
        rootBox.addView(pv);
        rootBox.addView(preview, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        updatePreview.run();

        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(ctx)
                .setTitle("调整日志浮窗")
                .setView(rootBox)
                .setPositiveButton("隐藏日志并关闭", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        c.save(ctx);
                        cfg = c;
                        hide();
                    }
                })
                .setNegativeButton("关闭", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        c.save(ctx);
                        cfg = c;
                        refresh();
                    }
                })
                .create();
        dlg.show();
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
        cb.setChecked(checked);
        cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean v) {
                setter.set(v);
                c.save(ctx);
                cfg = c;
                onChanged.run();
                refresh();
            }
        });
        return cb;
    }

    /** 一行滑条：名称+当前值，下方 SeekBar */
    private View sliderRow(String label, final int min, final int max, int cur, final IntSet setter,
                           final FloatConfig c, final Runnable onChanged) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dpf(10), 0, 0);

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
            cur.append("01:备注");
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
}
