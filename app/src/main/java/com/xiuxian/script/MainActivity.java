package com.xiuxian.script;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * 主界面：状态显示、无障碍设置跳转、悬浮窗权限、显示悬浮窗、启动/停止、配置编辑、日志滚动。
 */
public class MainActivity extends Activity {

    private TextView tvStatus;
    private TextView tvLog;
    private TextView tvGame;
    private Spinner spGame;
    private ScrollView logScroll;
    private String logBuffer = "";
    /** 换行符（避免源码里写转义序列） */
    private static final String NL = String.valueOf((char) 10);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // 防止脚本运行时屏幕休眠
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Logger.init(this);
        tvStatus = (TextView) findViewById(R.id.tv_status);
        tvLog = (TextView) findViewById(R.id.tv_log);
        logScroll = (ScrollView) findViewById(R.id.log_scroll);
        tvLog.setMovementMethod(new ScrollingMovementMethod());

        // ---- 游戏选择：换皮游戏靠包名区分，这里决定本次跑哪一个 ----
        tvGame = (TextView) findViewById(R.id.tv_game);
        spGame = (Spinner) findViewById(R.id.sp_game);
        ArrayAdapter<String> gameAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, Games.labels(this));
        gameAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spGame.setAdapter(gameAdapter);
        spGame.setSelection(Games.selectedIndex(this));
        spGame.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Games.setSelectedIndex(MainActivity.this, position);
                refreshGameLine();
                Logger.i("已切换游戏: " + Games.name(position) + " -> " + Games.pkg(position));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        refreshGameLine();

        // 日志监听：新日志到达就追加并滚到底部
        Logger.setListener(new Logger.Listener() {
            @Override
            public void onLog(String line) {
                appendLog(line);
                refreshStatus();
            }
        });

        Button btnService = (Button) findViewById(R.id.btn_service);
        Button btnOverlay = (Button) findViewById(R.id.btn_overlay);
        Button btnFloat = (Button) findViewById(R.id.btn_float);
        Button btnConfig = (Button) findViewById(R.id.btn_config);
        Button btnStart = (Button) findViewById(R.id.btn_start);
        Button btnStop = (Button) findViewById(R.id.btn_stop);
        Button btnClear = (Button) findViewById(R.id.btn_clearlog);

        btnService.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAccessibilitySettings();
            }
        });

        btnOverlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestOverlayPermission();
            }
        });

        btnFloat.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!canDrawOverlay()) {
                    toast("请先授予悬浮窗权限");
                    requestOverlayPermission();
                    return;
                }
                FloatWindow fw = FloatWindow.get(MainActivity.this);
                if (fw.isShowing()) {
                    fw.hide();
                } else {
                    fw.show();
                }
            }
        });

        btnConfig.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showConfigEditor();
            }
        });

        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AutoService s = AutoService.get();
                if (s == null) {
                    toast("请先开启无障碍服务");
                    Logger.e("无障碍服务未连接，无法启动");
                    openAccessibilitySettings();
                    return;
                }
                int gi = Games.selectedIndex(MainActivity.this);
                if (!Games.isInstalled(MainActivity.this, gi)) {
                    String miss = "选中的游戏未安装: " + Games.pkg(gi);
                    toast(miss);
                    Logger.e(miss);
                    return;
                }
                Logger.i("本次运行目标: " + Games.name(gi) + " -> " + Games.pkg(gi));
                s.startScript();
                refreshStatus();
            }
        });

        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AutoService s = AutoService.get();
                if (s != null) {
                    s.stopScript();
                } else {
                    toast("服务未连接");
                }
                refreshStatus();
            }
        });

        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logBuffer = "";
                tvLog.setText("");
                Logger.clear();
            }
        });

        // 把已有日志一次性铺上
        reloadLogs();
        refreshStatus();
        Logger.i("界面就绪；日志目录: " + Logger.logDirPath());
        Logger.i("启动方式: 按包名精确启动，避免换皮游戏串台");
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    // ================= 日志 =================

    private void appendLog(String line) {
        logBuffer = logBuffer + line + NL;
        // 只保留最近 400 行，避免界面越跑越卡
        String[] all = logBuffer.split(NL);
        if (all.length > 400) {
            StringBuilder sb = new StringBuilder();
            for (int i = all.length - 400; i < all.length; i++) {
                sb.append(all[i]).append(NL);
            }
            logBuffer = sb.toString();
        }
        tvLog.setText(logBuffer);
        logScroll.post(new Runnable() {
            @Override
            public void run() {
                logScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private void reloadLogs() {
        String[] lines = Logger.snapshot();
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(l).append(NL);
        }
        logBuffer = sb.toString();
        tvLog.setText(logBuffer);
    }

    // ================= 状态 =================

    private void refreshStatus() {
        AutoService s = AutoService.get();
        StringBuilder sb = new StringBuilder();
        sb.append("无障碍服务: ").append(s != null ? "已连接" : "未连接").append(NL);
        if (s != null) {
            sb.append("脚本状态: ").append(s.isRunning() ? "运行中" : "待机").append(NL);
            int[] size = s.screenSize();
            sb.append("屏幕: ").append(size[0]).append(" x ").append(size[1]).append(NL);
        }
        sb.append("悬浮窗权限: ").append(canDrawOverlay() ? "已授予" : "未授予").append(NL);
        sb.append("Android: ").append(Build.VERSION.RELEASE)
                .append("  /  日志: ").append(Logger.logDirPath());
        tvStatus.setText(sb.toString());
    }

    private void refreshGameLine() {
        if (tvGame == null) {
            return;
        }
        int i = Games.selectedIndex(this);
        boolean ok = Games.isInstalled(this, i);
        String s = "当前游戏: " + Games.name(i) + "  (" + Games.pkg(i) + ")  "
                + (ok ? "[已安装]" : "[未安装，请先装到雷电里]");
        tvGame.setText(s);
        if (tvGame != null) {
            tvGame.setTextColor(ok ? 0xFF1B7A1B : 0xFFB00020);
        }
    }

    // ================= 权限入口 =================

    private void openAccessibilitySettings() {
        try {
            Intent it = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
            toast("请在列表中找到“修仙脚本工具”并打开");
        } catch (Throwable t) {
            toast("无法打开无障碍设置: " + t);
        }
    }

    private boolean canDrawOverlay() {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        return Settings.canDrawOverlays(this);
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT < 23) {
            toast("当前系统无需单独授权");
            return;
        }
        try {
            Intent it = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
        } catch (Throwable t) {
            toast("无法打开悬浮窗权限页: " + t);
        }
    }

    // ================= 配置编辑（第一版用弹窗直接改 JSON） =================

    private void showConfigEditor() {
        final EditText input = new EditText(this);
        input.setTextSize(12);
        input.setSingleLine(false);
        try {
            JSONObject o = AutoService.loadConfig(this);
            input.setText(o.toString(2));
        } catch (Throwable t) {
            input.setText(AutoService.defaultConfigText());
        }

        ScrollView sc = new ScrollView(this);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                FloatWindow.dp(this, 260));
        input.setLayoutParams(ip);
        sc.addView(input);

        new android.app.AlertDialog.Builder(this)
                .setTitle("流程配置 (JSON)")
                .setView(sc)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        boolean ok = AutoService.saveConfig(MainActivity.this, input.getText().toString());
                        toast(ok ? "已保存，下次启动生效" : "保存失败：JSON 格式不正确");
                    }
                })
                .setNeutralButton("恢复默认", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        AutoService.saveConfig(MainActivity.this, AutoService.defaultConfigText());
                        toast("已恢复默认配置");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
