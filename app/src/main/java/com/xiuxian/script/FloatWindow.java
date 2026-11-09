package com.xiuxian.script;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 悬浮窗：一个可拖动的小球，点击展开后能看到“启动/停止/隐藏”。
 * 全部尺寸用 dp，适配 360x600 与其它分辨率。
 */
public class FloatWindow {

    private final Context ctx;
    private final WindowManager wm;
    private View root;
    private WindowManager.LayoutParams lp;

    private TextView status;
    private Button btnStart;
    private Button btnStop;

    private int screenW;
    private int screenH;

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
        if (root != null || wm == null) {
            return;
        }
        try {
            android.util.DisplayMetrics m = ctx.getResources().getDisplayMetrics();
            screenW = m.widthPixels;
            screenH = m.heightPixels;
        } catch (Throwable ignored) {
        }

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xDD222222);
        int pad = dp(ctx, 6);
        box.setPadding(pad, pad, pad, pad);

        status = new TextView(ctx);
        status.setTextColor(0xFF66FF66);
        status.setTextSize(11);
        status.setText("休眠中");

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);

        btnStart = new Button(ctx);
        btnStart.setText("启动");
        btnStart.setTextSize(11);
        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AutoService s = AutoService.get();
                if (s == null) {
                    Logger.e("无障碍服务未开启，无法启动脚本");
                    toast("请先在主界面开启无障碍服务");
                    return;
                }
                s.startScript();
                refreshStatus();
            }
        });

        btnStop = new Button(ctx);
        btnStop.setText("停止");
        btnStop.setTextSize(11);
        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AutoService s = AutoService.get();
                if (s != null) {
                    s.stopScript();
                }
                refreshStatus();
            }
        });

        Button btnHide = new Button(ctx);
        btnHide.setText("隐藏");
        btnHide.setTextSize(11);
        btnHide.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hide();
            }
        });

        row.addView(btnStart);
        row.addView(btnStop);
        row.addView(btnHide);
        box.addView(status);
        box.addView(row);

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

        // 拖动：按住任意位置移动整块悬浮窗
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
        }
    }

    public void hide() {
        if (root == null || wm == null) {
            return;
        }
        try {
            wm.removeView(root);
        } catch (Throwable ignored) {
        }
        root = null;
        Logger.i("悬浮窗已隐藏");
    }

    /** 刷新悬浮窗上的运行状态文字 */
    public void refreshStatus() {
        if (status == null) {
            return;
        }
        AutoService s = AutoService.get();
        if (s == null) {
            status.setText("服务未开");
        } else if (s.isRunning()) {
            status.setText("运行中");
        } else {
            status.setText("待机");
        }
    }

    private void toast(String msg) {
        try {
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }
}
