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
import android.widget.ImageView;
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

        Button btnScan = (Button) findViewById(R.id.btn_scan);
        btnScan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showScanDialog();
            }
        });

        Button btnLib = (Button) findViewById(R.id.btn_library);
        btnLib.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLibrary();
            }
        });

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

    /**
     * 安装包库：列出 GitHub Releases 里的历史版本 APK，点一项即可下载安装。
     * 这样换手机后不用再到处找安装包。
     */
    private void showLibrary() {
        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setMessage("正在读取版本库...");
        pd.setCancelable(false);
        pd.show();

        Installers.fetch(new Installers.Callback() {
            @Override
            public void onResult(java.util.List<Installers.Item> items, String error) {
                try {
                    pd.dismiss();
                } catch (Throwable ignored) {
                }
                if (error != null) {
                    new android.app.AlertDialog.Builder(MainActivity.this)
                            .setTitle("读取版本库失败")
                            .setMessage(error + NL + NL + "版本库地址：" + NL
                                    + "https://github.com/"
                                    + Installers.OWNER + "/" + Installers.REPO + "/releases")
                            .setPositiveButton("知道了", null)
                            .show();
                    return;
                }
                if (items == null || items.isEmpty()) {
                    new android.app.AlertDialog.Builder(MainActivity.this)
                            .setTitle("版本库里还没有安装包")
                            .setMessage("把 APK 上传到 Releases 的 Assets 里，这里就会自动出现。" + NL + NL
                                    + "https://github.com/" + Installers.OWNER + "/"
                                    + Installers.REPO + "/releases")
                            .setPositiveButton("知道了", null)
                            .show();
                    return;
                }

                // 卡片式列表：每个安装包独立一块，图标 + 中文名 + 大小 + 独立下载按钮
                ScrollView sv = new ScrollView(MainActivity.this);
                LinearLayout box = new LinearLayout(MainActivity.this);
                box.setOrientation(LinearLayout.VERTICAL);
                int pad = dp(10);
                box.setPadding(pad, pad, pad, pad);
                sv.addView(box, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

                for (int i = 0; i < items.size(); i++) {
                    box.addView(buildLibCard(items.get(i)));
                }

                new android.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle("安装包库（共 " + items.size() + " 个）")
                        .setView(sv)
                        .setNegativeButton("关闭", null)
                        .show();
            }
        });
    }

    /** 安装包库里的一张卡片：游戏图标 + 中文名/版本 + 大小日期 + 下载按钮 */
    private View buildLibCard(final Installers.Item it) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(android.view.Gravity.CENTER_VERTICAL);
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(8);
        card.setLayoutParams(clp);

        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFFF7F8FA);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), 0xFFE4E7EC);
        card.setBackgroundDrawable(bg);

        // 游戏图标：已安装就读真实图标，没装则用彩色圆标兜底
        ImageView iv = new ImageView(this);
        android.graphics.drawable.Drawable d = Installers.icon(this, it.pkg());
        if (d != null) {
            iv.setImageDrawable(d);
        } else {
            iv.setImageDrawable(badgeIcon(it.label()));
        }
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(44), dp(44));
        ilp.rightMargin = dp(10);
        card.addView(iv, ilp);

        // 中间两行文字
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = new TextView(this);
        String v = it.version();
        t1.setText(v.isEmpty() ? it.label() : (it.label() + "  " + v));
        t1.setTextSize(16);
        t1.setTextColor(0xFF1F2328);
        col.addView(t1);

        TextView t2 = new TextView(this);
        String sub = it.sizeText();
        if (!it.published.isEmpty()) {
            sub = sub.isEmpty() ? it.published : (sub + "  " + it.published);
        }
        t2.setText(sub);
        t2.setTextSize(12);
        t2.setTextColor(0xFF8A9099);
        col.addView(t2);
        card.addView(col, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // 独立下载按钮
        Button btn = new Button(this);
        btn.setText("下载");
        btn.setAllCaps(false);
        btn.setTextSize(14);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Installers.download(MainActivity.this, it);
                Toast.makeText(MainActivity.this,
                        "已开始下载：" + it.fileName(), Toast.LENGTH_SHORT).show();
            }
        });
        card.addView(btn, new LinearLayout.LayoutParams(dp(76),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return card;
    }

    /** 没装游戏时的占位图标：按渠道取色 + 首字 */
    private android.graphics.drawable.Drawable badgeIcon(String label) {
        int size = dp(44);
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(size, size,
                android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bmp);
        android.graphics.Paint p = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);

        int color = 0xFF3A7D44;
        if (label.contains("4399")) {
            color = 0xFFE4572E;
        } else if (label.contains("百度")) {
            color = 0xFF2F6FED;
        } else if (label.contains("怪猫")) {
            color = 0xFF8E44AD;
        } else if (label.contains("九游")) {
            color = 0xFF16A085;
        } else if (label.contains("聚乐")) {
            color = 0xFFD4A017;
        } else if (label.contains("模拟器")) {
            color = 0xFF607D8B;
        } else if (label.contains("小米")) {
            color = 0xFFFF6900;
        }
        p.setColor(color);
        c.drawCircle(size / 2f, size / 2f, size / 2f, p);

        String ch = label == null ? "" : label;
        int dot = ch.indexOf((char) 183);
        if (dot >= 0 && dot + 1 < ch.length()) {
            ch = ch.substring(dot + 1);
        }
        if (ch.isEmpty()) {
            ch = "包";
        }
        p.setColor(0xFFFFFFFF);
        p.setTextSize(dp(20));
        p.setTextAlign(android.graphics.Paint.Align.CENTER);
        android.graphics.Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(ch.substring(0, 1), size / 2f,
                size / 2f - (fm.ascent + fm.descent) / 2f, p);
        return new android.graphics.drawable.BitmapDrawable(getResources(), bmp);
    }

    /** dp 转像素：界面尺寸一律按 dp 写，兼容 360x600/160dpi */
    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 用系统下载器下载，文件名是中文（中文名 → 版本.apk） */
    private void openDownload(Installers.Item it) {
        Installers.download(this, it);
    }

    /**
     * 扫描设备已安装应用，勾选后加入游戏列表。
     * 这样用户不必提供 APK、也不必自己去查包名。
     */
    private void showScanDialog() {
        final java.util.List<Games.Game> cands = Games.scanInstalled(this);
        if (cands.isEmpty()) {
            toast("没有扫描到可启动的应用");
            return;
        }
        final String[] names = new String[cands.size()];
        final boolean[] checked = new boolean[cands.size()];
        for (int i = 0; i < cands.size(); i++) {
            Games.Game g = cands.get(i);
            names[i] = g.name + "\n" + g.pkg + (g.note.isEmpty() ? "" : ("  ·  " + g.note));
            checked[i] = Games.indexOfPkg(this, g.pkg) >= 0;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("勾选要跑的游戏（共 " + cands.size() + " 个）")
                .setMultiChoiceItems(names, checked,
                        new android.content.DialogInterface.OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int which,
                                                boolean isChecked) {
                                checked[which] = isChecked;
                            }
                        })
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        Games.applySelection(MainActivity.this, cands, checked);
                        reloadGameSpinner();
                        toast("已更新，共 " + Games.count() + " 个游戏");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 重新灌一次下拉数据（扫描保存后调用） */
    private void reloadGameSpinner() {
        if (spGame == null) {
            return;
        }
        ArrayAdapter<String> a = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, Games.labels(this));
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spGame.setAdapter(a);
        int idx = Games.selectedIndex(this);
        if (idx >= 0 && idx < Games.count()) {
            spGame.setSelection(idx);
        }
        refreshGameLine();
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
