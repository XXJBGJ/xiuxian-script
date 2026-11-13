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
import android.widget.CheckBox;
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
    private TextView tvGameVer;
    private Spinner spGame;
    private ScrollView logScroll;
    private View logPanel;
    private View btnLogExpand;
    private View btnLogCollapse;
    /** 运行日志默认收起，点底部上拉箭头才展开 */
    private boolean logExpanded = false;
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

        // ---- 运行日志折叠：默认隐藏，底部上拉箭头展开，日志顶部下拉箭头收起 ----
        logPanel = findViewById(R.id.log_panel);
        btnLogExpand = findViewById(R.id.btn_log_expand);
        btnLogCollapse = findViewById(R.id.btn_log_collapse);
        if (btnLogExpand != null) {
            btnLogExpand.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setLogExpanded(true);
                }
            });
        }
        if (btnLogCollapse != null) {
            btnLogCollapse.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setLogExpanded(false);
                }
            });
        }
        setLogExpanded(false);

        // ---- 游戏信息卡：显示当前游戏 + 版本，点整张卡片即可换游戏/扫描 ----
        tvGame = (TextView) findViewById(R.id.tv_game);
        tvGameVer = (TextView) findViewById(R.id.tv_game_ver);
        View cardGame = findViewById(R.id.card_game);
        if (cardGame != null) {
            cardGame.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showGamePicker();
                }
            });
        }
        refreshGameLine();

        // 四个标签页：脚本 / 日常 / 仙榜 / 活动
        setupTabs();

        Button btnLib = (Button) findViewById(R.id.btn_library);
        btnLib.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLibrary();
            }
        });

        Button btnAcc = (Button) findViewById(R.id.btn_accounts);
        btnAcc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAccounts();
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

        Button btnPerm = (Button) findViewById(R.id.btn_perm);
        Button btnConfig = (Button) findViewById(R.id.btn_config);
        Button btnStart = (Button) findViewById(R.id.btn_start);
        Button btnStop = (Button) findViewById(R.id.btn_stop);
        Button btnClear = (Button) findViewById(R.id.btn_clearlog);
        Button btnLogCopy = (Button) findViewById(R.id.btn_log_copy);

        btnPerm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPermissionPanel();
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
                // 两个权限缺一不可：无障碍 + 悬浮窗。
                // 缺哪个就自动跳到哪个授权页，用户点完返回本界面会自动接着启动。
                if (!ensurePermissionsThenStart()) {
                    return;
                }
                startScriptNow();
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
                try {
                    FloatWindow.get(MainActivity.this).refresh();
                } catch (Throwable ignored) {
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

        // 一键复制日志：直接粘到聊天窗口发给我，不用满手机找文件
        btnLogCopy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyLogToClipboard();
            }
        });

        // 把已有日志一次性铺上
        reloadLogs();
        refreshStatus();
        Logger.i("界面就绪；日志目录: " + Logger.logDirPath());

        // 启动时做一次「权限自愈」：用 root 把无障碍和悬浮窗补好，
        // 之后关掉界面再打开就不需要手动再开关一遍。
        // 放后台线程，避免 su 首次弹授权时卡住界面。
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean ok = RootGrant.ensureAll(MainActivity.this);
                    if (!ok) {
                        boolean acc = RootGrant.isAccessibilityOn(MainActivity.this);
                        boolean ovl = RootGrant.canDrawOverlay(MainActivity.this);
                        if (!acc || !ovl) {
                            Logger.w("权限未就绪（无障碍=" + acc + " 悬浮窗=" + ovl
                                    + "），点「启动脚本」会自动带你去授权");
                        }
                    }
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            refreshStatus();
                        }
                    });
                } catch (Throwable t) {
                    Logger.w("权限自愈异常: " + t.getClass().getSimpleName());
                }
            }
        }).start();
        Logger.i("启动方式: 按包名精确启动，避免换皮游戏串台");
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        // 悬浮球常驻：只要有悬浮窗权限就显示（不依赖脚本是否运行）
        try {
            if (canDrawOverlay()) {
                FloatBall.get(this).show();
            }
        } catch (Throwable t) {
            Logger.e("悬浮球显示失败: " + t);
        }
        // 从授权页返回：无障碍功能/悬浮窗 一键修复流程继续
        if (permFlow) {
            boolean accOk = isAccessibilityOn() || AutoService.get() != null;
            boolean ovlOk = canDrawOverlay();
            if (accOk && ovlOk) {
                continuePermFlow();
            } else if ("acc".equals(pendingPerm) && accOk && !ovlOk) {
                pendingPerm = "overlay";
                requestOverlayPermission();
            }
        }
        // 从授权页返回：权限齐了就自动接着启动，中途还需补另一个权限时继续跳
        if (pendingStart) {
            boolean acc = isAccessibilityOn() || AutoService.get() != null;
            boolean overlay = canDrawOverlay();
            if (acc && overlay) {
                pendingStart = false;
                pendingPerm = "";
                Logger.i("两个权限都已就绪，自动开始启动脚本");
                startScriptNow();
            } else if ("acc".equals(pendingPerm) && acc && !overlay) {
                // 无障碍刚开好，还差悬浮窗，自动跳第二个授权页
                pendingPerm = "overlay";
                toast("还差「显示在应用上层」，已自动跳转");
                requestOverlayPermission();
            } else if (acc && overlay) {
                pendingStart = false;
            }
        }
    }

    // ================= 日志 =================

    /** 展开 / 收起运行日志；收起时页面底部只留一条上拉箭头 */
    private void setLogExpanded(boolean expanded) {
        logExpanded = expanded;
        if (logPanel != null) {
            logPanel.setVisibility(expanded ? View.VISIBLE : View.GONE);
        }
        if (btnLogExpand != null) {
            // 展开后底部箭头就没用了，藏起来把空间全给日志
            btnLogExpand.setVisibility(expanded ? View.GONE : View.VISIBLE);
        }
        if (expanded && logScroll != null) {
            logScroll.post(new Runnable() {
                @Override
                public void run() {
                    logScroll.fullScroll(View.FOCUS_DOWN);
                }
            });
        }
    }

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
        String sp = Games.selectedPkg(this);
        sb.append("账号: 已勾选 ").append(Accounts.checkedCount(this, sp))
                .append(" / 共 ").append(Accounts.count(this, sp))
                .append(" 个（只会跑勾选的）").append(NL);
        sb.append("悬浮窗权限: ").append(canDrawOverlay() ? "已授予" : "未授予").append(NL);
        sb.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" / 日志: ").append(Logger.logDirPath());
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
        // 优先用内置的游戏图标（已按渠道包内真实图标抽出来打进 APK），
        // 这样没装游戏也能看到牌子；仍取不到才退回彩色圆标。
        android.graphics.drawable.Drawable d = null;
        // 图标优先取构建时从游戏 APK 里读出的真实启动图标，
        // 这样安装包库显示的图，和装上之后桌面上的图标完全一致；取不到再回退内置图。
        String iconKey = BundledGameIcons.keyFor(it.label(), it.pkg(), it.name);
        android.graphics.Bitmap bm = GameIcons.get(iconKey);
        if (bm != null) {
            d = new android.graphics.drawable.BitmapDrawable(getResources(), bm);
        }
        if (d == null) {
            d = Installers.icon(this, it.pkg());
        }
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

    /** 选择游戏后刷新界面上的游戏信息卡（原来是灌下拉框的地方） */
    private void reloadGameSpinner() {
        refreshGameLine();
    }

    private void refreshGameLine() {
        if (tvGame == null) {
            return;
        }
        int i = Games.selectedIndex(this);
        if (i < 0 || i >= Games.count()) {
            tvGame.setText("当前游戏: --");
            if (tvGameVer != null) {
                tvGameVer.setText("点这里扫描并选择游戏");
            }
            return;
        }
        boolean ok = Games.isInstalled(this, i);
        String s = "当前游戏: " + Games.name(i) + "  (" + Games.pkg(i) + ")  "
                + (ok ? "[已安装]" : "[未安装]");
        tvGame.setText(s);
        tvGame.setTextColor(ok ? 0xFF1B7A1B : 0xFFB00020);
        if (tvGameVer != null) {
            String v = gameVersion(Games.pkg(i));
            String note = Games.note(i);
            String line = Games.name(i) + (v.isEmpty() ? "" : ("  ·  " + v));
            if (note != null && note.length() > 0) {
                line = line + "  " + note;
            }
            tvGameVer.setText(line);
        }
    }

    /** 读已安装游戏的版本号，没装返回空 */
    private String gameVersion(String pkg) {
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(pkg, 0);
            return pi.versionName == null ? "" : pi.versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    // ================= 选游戏（合并了原来单独的扫描窗口） =================

    /**
     * 点游戏信息卡弹出：列出当前游戏，选中即可切换；
     * 最后一项「扫描已安装应用」就地进入扫描勾选，扫完回到这里。
     */
    private void showGamePicker() {
        final String[] labels = Games.labels(this);
        final int n = (labels == null) ? 0 : labels.length;
        final String[] items = new String[n + 1];
        for (int i = 0; i < n; i++) {
            items[i] = labels[i];
        }
        items[n] = "＋ 扫描并选择游戏（自动找已安装的）";

        int cur = Games.selectedIndex(this);
        if (cur < 0 || cur >= n) {
            cur = 0;
        }

        new android.app.AlertDialog.Builder(this)
                .setTitle("当前游戏（点一项切换）")
                .setSingleChoiceItems(items, n == 0 ? n : cur,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int which) {
                                d.dismiss();
                                if (which >= n) {
                                    showScanDialog();
                                    return;
                                }
                                Games.setSelectedIndex(MainActivity.this, which);
                                reloadGameSpinner();
                                refreshStatus();
                                Logger.i("已切换游戏: " + Games.name(which)
                                        + " -> " + Games.pkg(which));
                            }
                        })
                .setNegativeButton("关闭", null)
                .show();
    }

    // ================= 无障碍功能 / 悬浮窗（三合一） =================

    /** 无障碍 + 悬浮窗权限 合并状态标记，用于从系统授权页返回后自动继续 */
    private boolean permFlow = false;

    /** 三个功能合一个入口：无障碍设置 + 悬浮窗权限 + 显示悬浮窗 */
    private void showPermissionPanel() {
        boolean acc = isAccessibilityOn() || AutoService.get() != null;
        boolean ovl = canDrawOverlay();
        boolean showing = false;
        try {
            showing = FloatWindow.get(this).isShowing();
        } catch (Throwable ignored) {
        }
        StringBuilder sb = new StringBuilder();
        sb.append("无障碍服务: ").append(acc ? "已开启" : "未开启").append(NL);
        sb.append("悬浮窗权限: ").append(ovl ? "已授予" : "未授予").append(NL);
        sb.append("悬浮窗: ").append(showing ? "显示中" : "未显示").append(NL);
        sb.append(NL).append("点「一键修复」会用 root 自动把这两项权限补好，")
                .append("并叫出悬浮窗和悬浮球；")
                .append("补不上的会跳到系统设置页，回来自动继续。");

        new android.app.AlertDialog.Builder(this)
                .setTitle("无障碍功能/悬浮窗")
                .setMessage(sb.toString())
                .setPositiveButton("一键修复", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        permFlow = true;
                        continuePermFlow();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 一键修复权限的流程：先 root 自动补，补不上就跳系统页，回来自动接着走 */
    private void continuePermFlow() {
        boolean acc = isAccessibilityOn() || AutoService.get() != null;
        boolean ovl = canDrawOverlay();

        if (acc && ovl) {
            permFlow = false;
            pendingPerm = "";
            boolean showing = false;
            try {
                FloatWindow fw = FloatWindow.get(this);
                showing = fw.isShowing();
                if (!showing) {
                    fw.show();
                }
            } catch (Throwable t) {
                Logger.w("悬浮窗显示失败: " + t);
            }
            try {
                FloatBall.get(this).show();
            } catch (Throwable ignored) {
            }
            toast(showing ? "无障碍与悬浮窗都已就绪" : "无障碍与悬浮窗都已就绪，悬浮窗已显示");
            Logger.i("无障碍与悬浮窗就绪，悬浮窗" + (showing ? "原本已显示" : "已显示"));
            refreshStatus();
            return;
        }

        if (RootGrant.ensureAll(this)) {
            boolean acc2 = RootGrant.isAccessibilityOn(this) || AutoService.get() != null;
            boolean ovl2 = RootGrant.canDrawOverlay(this);
            if (acc2 && ovl2) {
                toast("权限已自动恢复");
                continuePermFlow();
                return;
            }
        }

        if (!acc) {
            pendingPerm = "acc";
            Logger.w("未开启无障碍，已跳转无障碍设置页");
            toast("请开启「修仙脚本工具」无障碍，返回后自动继续");
            openAccessibilitySettings();
        } else {
            pendingPerm = "overlay";
            Logger.w("未授予悬浮窗权限，已跳转授权页");
            toast("请允许「显示在应用上层」，返回后自动继续");
            requestOverlayPermission();
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

    /**
     * 无障碍是否已开启：直接读系统里的已启用服务列表，
     * 比「服务是否已连接」更准，刚授权返回时也能立刻判断。
     */
    private boolean isAccessibilityOn() {
        return RootGrant.isAccessibilityOn(this);
    }

    /** 权限自动补齐后要继续启动的标记 */
    private boolean pendingStart = false;
    /** 刚跳转过哪个授权页，用于返回后判断是否继续 */
    private String pendingPerm = "";

    /**
     * 启动前检查两个权限：无障碍 + 悬浮窗，缺哪个就自动跳对应授权页。
     * 用户授权完返回本界面，onResume 会自动接着启动，不用再点一次。
     *
     * @return true 表示两个权限都齐了，可以立即启动
     */
    private boolean ensurePermissionsThenStart() {
        AutoService s = AutoService.get();
        boolean acc = isAccessibilityOn() || s != null;
        boolean overlay = canDrawOverlay();

        if (acc && overlay) {
            return true;
        }

        // 缺权限时先让 root 试一次（雷电默认有 root，通常一补就好，
        // 用户就不必每次手动去设置里点开关了）
        if (RootGrant.ensureAll(this)) {
            acc = RootGrant.isAccessibilityOn(this) || AutoService.get() != null;
            overlay = RootGrant.canDrawOverlay(this);
            if (acc && overlay) {
                toast("权限已自动恢复，直接开始");
                return true;
            }
        }

        pendingStart = true;

        if (!acc) {
            pendingPerm = "acc";
            Logger.w("未开启无障碍，已自动跳到无障碍设置页，请打开本服务后返回");
            toast("请开启「修仙脚本工具」无障碍，返回后自动继续");
            openAccessibilitySettings();
            return false;
        }
        // 无障碍已开，只差悬浮窗
        pendingPerm = "overlay";
        Logger.w("未授予悬浮窗权限，已自动跳到授权页，请打开后返回");
        toast("请允许「显示在应用上层」，返回后自动继续");
        requestOverlayPermission();
        return false;
    }

    /** 真正启动脚本：确认游戏已安装，叫出浮窗，然后开始 */
    private void startScriptNow() {
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
        // 启动脚本时自动把进度悬浮窗叫出来
        try {
            FloatWindow fw = FloatWindow.get(MainActivity.this);
            fw.show();
            fw.expand();
            FloatBall.get(MainActivity.this).show();
        } catch (Throwable t) {
            Logger.w("悬浮窗自动显示失败: " + t);
        }
        s.startScript();
        refreshStatus();
    }

    private boolean canDrawOverlay() {
        return RootGrant.canDrawOverlay(this);
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

    // ================= 账号管理 =================

    /**
     * 账号管理：先选游戏，再管这个游戏的账号。
     * 天途和仙命决 7 个渠道各自一套，互不干扰；
     * 账号密码用 & 隔开，一行一个，可多行批量粘贴。
     */
    private void showAccounts() {
        final Spinner sp = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, Games.labels(this));
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        int cur = Games.selectedIndex(this);
        if (cur >= 0 && cur < Games.count()) {
            sp.setSelection(cur);
        }

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);

        final TextView tvInfo = new TextView(this);
        tvInfo.setTextSize(12);
        tvInfo.setTextColor(0xFF5A6472);
        tvInfo.setPadding(0, dp(6), 0, dp(6));
        root.addView(tvInfo);

        final LinearLayout listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        final ScrollView sv = new ScrollView(this);
        sv.addView(listBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(250)));

        // 刷新列表：用数组包一层，方便按钮和勾选框回调里调用
        final Runnable[] refresh = new Runnable[1];
        refresh[0] = new Runnable() {
            @Override
            public void run() {
                int gi = sp.getSelectedItemPosition();
                if (gi < 0 || gi >= Games.count()) {
                    return;
                }
                String pkg = Games.pkg(gi);
                // 与首页选择保持一致：这里换游戏，首页也跟着换
                Games.setSelectedIndex(MainActivity.this, gi);
                listBox.removeAllViews();
                java.util.List<Accounts.Acc> l = Accounts.list(MainActivity.this, pkg);
                int ck = 0;
                for (int i = 0; i < l.size(); i++) {
                    if (l.get(i).checked) {
                        ck++;
                    }
                }
                tvInfo.setText("游戏: " + Games.name(gi) + "    包名: " + pkg + NL
                        + "账号 " + l.size() + " 个，已勾选 " + ck
                        + " 个（启动脚本只跑勾选的，没勾的直接跳过）");
                if (l.isEmpty()) {
                    TextView t = new TextView(MainActivity.this);
                    t.setText("这个游戏还没有账号。" + NL
                            + "点下面「添加账号」，按「账号&密码」一行一个填进去，"
                            + NL + "一行写一组，可以一次粘贴很多行。");
                    t.setTextSize(12);
                    t.setTextColor(0xFF8A9099);
                    t.setPadding(dp(4), dp(10), dp(4), dp(10));
                    listBox.addView(t);
                }
                for (int i = 0; i < l.size(); i++) {
                    listBox.addView(buildAccRow(pkg, i, refresh[0]));
                }
            }
        };

        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                refresh[0].run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        // ---- 第一排：添加账号 / 删除勾选 ----
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        Button bAdd = new Button(this);
        bAdd.setText("添加账号");
        bAdd.setAllCaps(false);
        bAdd.setTextSize(13);
        bAdd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int gi = sp.getSelectedItemPosition();
                showAddAccount(Games.pkg(gi), refresh[0]);
            }
        });
        Button bDel = new Button(this);
        bDel.setText("删除勾选");
        bDel.setAllCaps(false);
        bDel.setTextSize(13);
        bDel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final int gi = sp.getSelectedItemPosition();
                final String pkg = Games.pkg(gi);
                new android.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle("删除勾选的账号")
                        .setMessage("将删除「" + Games.name(gi) + "」里所有勾选的账号，删除后不可恢复。")
                        .setPositiveButton("确认删除", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                int n = Accounts.removeChecked(MainActivity.this, pkg);
                                toast(n > 0 ? ("已删除 " + n + " 个账号") : "没有勾选任何账号");
                                refresh[0].run();
                                reloadGameSpinner();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
        row1.addView(bAdd, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row1.addView(bDel, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row1);

        // ---- 第二排：全选 / 全不选 / 显示密码 ----
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        Button bAll = new Button(this);
        bAll.setText("全选");
        bAll.setAllCaps(false);
        bAll.setTextSize(13);
        bAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Accounts.setAllChecked(MainActivity.this, Games.pkg(sp.getSelectedItemPosition()), true);
                refresh[0].run();
            }
        });
        Button bNone = new Button(this);
        bNone.setText("全不选");
        bNone.setAllCaps(false);
        bNone.setTextSize(13);
        bNone.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Accounts.setAllChecked(MainActivity.this, Games.pkg(sp.getSelectedItemPosition()), false);
                refresh[0].run();
            }
        });
        final Button bShow = new Button(this);
        bShow.setText(Accounts.isShowPass() ? "隐藏密码" : "显示密码");
        bShow.setAllCaps(false);
        bShow.setTextSize(13);
        bShow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Accounts.setShowPass(!Accounts.isShowPass());
                bShow.setText(Accounts.isShowPass() ? "隐藏密码" : "显示密码");
                refresh[0].run();
            }
        });
        row2.addView(bAll, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row2.addView(bNone, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row2.addView(bShow, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row2);

        TextView tip = new TextView(this);
        tip.setText("账号密码只保存在这台手机/模拟器里，不上传。换设备需要重新输入。");
        tip.setTextSize(11);
        tip.setTextColor(0xFF8A9099);
        tip.setPadding(0, dp(8), 0, 0);
        root.addView(tip);

        new android.app.AlertDialog.Builder(this)
                .setTitle("账号管理")
                .setView(root)
                .setNegativeButton("关闭", null)
                .show();
        refresh[0].run();
    }

    /** 一行一个账号：勾选框 + 序号.账号 + 删除按钮 */
    private View buildAccRow(final String pkg, final int idx, final Runnable refresh) {
        java.util.List<Accounts.Acc> l = Accounts.list(this, pkg);
        if (idx < 0 || idx >= l.size()) {
            return new View(this);
        }
        final Accounts.Acc a = l.get(idx);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(6), dp(6), dp(6));

        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFFF7F8FA);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), 0xFFE4E7EC);
        row.setBackgroundDrawable(bg);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(6);
        row.setLayoutParams(rlp);

        final CheckBox cb = new CheckBox(this);
        cb.setChecked(a.checked);
        // 先设状态再挂监听，避免刷新时自己触发自己
        cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean isChecked) {
                Accounts.setChecked(MainActivity.this, pkg, idx, isChecked);
                cb.post(refresh);
            }
        });
        row.addView(cb, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView tv = new TextView(this);
        tv.setText((idx + 1) + ". " + a.show());
        tv.setTextSize(14);
        tv.setTextColor(a.checked ? 0xFF1F2328 : 0xFF8A9099);
        tv.setPadding(dp(4), 0, dp(4), 0);
        row.addView(tv, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button del = new Button(this);
        del.setText("删除");
        del.setAllCaps(false);
        del.setTextSize(12);
        del.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Accounts.remove(MainActivity.this, pkg, idx);
                toast("已删除 1 个账号");
                refresh.run();
                reloadGameSpinner();
            }
        });
        row.addView(del, new LinearLayout.LayoutParams(dp(64),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** 添加账号：多行文本框，一行一个「账号&密码」，可整段粘贴 */
    private void showAddAccount(final String pkg, final Runnable refresh) {
        final EditText et = new EditText(this);
        et.setTextSize(14);
        et.setSingleLine(false);
        et.setMinLines(4);
        et.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        et.setHint("一行一个，格式：账号&密码" + NL + "13800000000&abc123"
                + NL + "13900000000&def456");
        et.setPadding(dp(10), dp(10), dp(10), dp(10));

        new android.app.AlertDialog.Builder(this)
                .setTitle("添加账号")
                .setView(et)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        int n = Accounts.addOrUpdate(MainActivity.this, pkg,
                                et.getText().toString());
                        if (n > 0) {
                            toast("已保存 " + n + " 条账号");
                        } else {
                            toast("没识别到账号，格式应为 账号&密码");
                        }
                        refresh.run();
                        reloadGameSpinner();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 一键复制日志到剪贴板。
     *
     * 日志文件在 Android/data/com.xiuxian.script/files/logs 下面，
     * Android 11 以后系统禁止文件管理器进入该目录，用户根本打不开。
     * 所以这里直接复制，粘贴到聊天窗口即可。
     */
    private void copyLogToClipboard() {
        try {
            String text = "";
            try {
                text = Logger.fullText();
            } catch (Throwable ignored) {
            }
            if (text == null) {
                text = "";
            }
            // 文件读不出来时，退回界面上已经铺开的日志，保证一定有东西可复制
            if (text.trim().length() == 0 && logBuffer != null) {
                text = logBuffer;
            }
            if (text.trim().length() == 0) {
                toast("还没有日志，先点「启动脚本」跑一次");
                return;
            }
            int lines = text.split(NL).length;
            // 剪贴板要跨进程传输，整段太大（几十万字符）会静默失败，
            // 这里只保留最后 20 万字符，粘出来照样能看关键过程。
            final int CAP = 200000;
            if (text.length() > CAP) {
                text = "……（日志太长，只保留最后部分）……" + NL
                        + text.substring(text.length() - CAP);
            }
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                toast("系统剪贴板不可用");
                return;
            }
            cm.setPrimaryClip(android.content.ClipData.newPlainText("修仙脚本日志", text));
            toast("已复制 " + lines + " 行日志，粘贴发我就行");
            Logger.i("日志已复制到剪贴板（" + lines + " 行）");
        } catch (Throwable t) {
            toast("复制失败: " + t.getClass().getSimpleName());
            Logger.w("复制日志失败: " + t);
        }
    }


    // ================= 四个标签页 =================

    /** 当前选中的标签序号：0 脚本 / 1 日常 / 2 仙榜 / 3 活动 */
    private int curTab = 0;
    private View[] tabPages;
    private Button[] tabButtons;

    private void setupTabs() {
        tabButtons = new Button[]{
                (Button) findViewById(R.id.tab_script),
                (Button) findViewById(R.id.tab_daily),
                (Button) findViewById(R.id.tab_rank),
                (Button) findViewById(R.id.tab_activity)
        };
        tabPages = new View[]{
                findViewById(R.id.page_script),
                findViewById(R.id.page_daily),
                findViewById(R.id.page_rank),
                findViewById(R.id.page_activity)
        };
        for (int i = 0; i < tabButtons.length; i++) {
            final int idx = i;
            if (tabButtons[i] == null) continue;
            tabButtons[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchTab(idx);
                }
            });
        }
        switchTab(0);
    }

    private void switchTab(int idx) {
        curTab = idx;
        for (int i = 0; i < tabPages.length; i++) {
            if (tabPages[i] == null) continue;
            tabPages[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
            if (tabButtons[i] != null) {
                tabButtons[i].setTextColor(i == idx ? 0xFF1F1F1F : 0xFF8A8A8A);
                tabButtons[i].setTypeface(null, i == idx
                        ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            }
        }
        // 切到哪个页就现算哪个页，保证数据是最新的
        try {
            if (idx == 1) {
                Pages.renderDaily(this, (LinearLayout) findViewById(R.id.box_daily));
            } else if (idx == 2) {
                Pages.renderRank(this, (LinearLayout) findViewById(R.id.box_rank));
            } else if (idx == 3) {
                Pages.renderActivity(this, (LinearLayout) findViewById(R.id.box_activity));
            }
        } catch (Throwable t) {
            Logger.e("页面渲染失败: " + t);
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
