package com.xiuxian.script;

import android.app.DownloadManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 安装包库：从 GitHub Releases 自动列出游戏安装包，点一下就能下载。
 *
 * 中文名说明：
 * GitHub 的附件文件名只接受 ASCII，汉字会被服务端直接剥离，
 * 所以线上文件名只能是 tiantu-pupu-1.0.4.apk 这种拼音。
 * 中文显示名与中文下载文件名都放在本类的 MAP 里做映射，
 * 界面上看到的是中文，存到手机里的文件也是中文名。
 *
 * 只收录 MAP 里登记的游戏安装包：
 * 脚本自身的历次构建产物（xiuxian-script.apk、default.apk 等）一律不显示，
 * 所以安装包库不会再一次出现十几条同名英文包。
 */
public class Installers {

    /** 版本库所在仓库 */
    public static final String OWNER = "XXJBGJ";
    public static final String REPO = "xiuxian-script";

    /**
     * 线上文件名关键字 → { 中文显示名, 版本, 对应游戏包名 }
     * 新增版本时只要在这里加一行，界面与下载文件名就自动变中文。
     */
    private static final String[][] MAP = new String[][]{
            {"tiantu-pupu", "天途·噗噗", "1.0.4", "com.tt.spgw.qg"},
            {"xianmingjue-4399", "仙命决·4399", "2.5", "com.gm.xmj.m4399"},
            {"xianmingjue-baidu", "仙命决·百度", "1.0.1", "com.gm.xmj.g.baidu"},
            {"xianmingjue-guaimao", "仙命决·怪猫", "1.3.2", "com.knight.kungfu"},
            {"xianmingjue-jiuyou", "仙命决·九游", "1.7", "com.gm.xmj.aligames"},
            {"xianmingjue-jule", "仙命决·聚乐", "3.0", "com.xmj.wf.htc"},
            {"xianmingjue-moniqi", "仙命决·模拟器", "1.0.0", "com.xmj.twgmb1"},
            {"xianmingjue-xiaomi", "仙命决·小米", "1.2.4", "com.gm.xmj.mi"},
    };

    /** 是否为本库登记的游戏安装包（脚本自身产物返回 false） */
    public static boolean isGameInstaller(String fileName) {
        return mapIndex(fileName) < MAP.length;
    }

    /** 在 MAP 中的位置，未登记返回 MAP.length */
    private static int mapIndex(String fileName) {
        String n = fileName == null ? "" : fileName.toLowerCase();
        for (int i = 0; i < MAP.length; i++) {
            if (n.contains(MAP[i][0])) {
                return i;
            }
        }
        return MAP.length;
    }

    public static class Item {

        public String tag = "";
        public String title = "";
        public String name = "";
        public String url = "";
        public long size = 0;
        public String published = "";

        /** 中文显示名；MAP 里没有的按文件名整理出一个可读名字 */
        public String label() {
            String[] m = match();
            if (m != null) {
                return m[1];
            }
            String s = name == null ? "" : name;
            int p = s.toLowerCase().lastIndexOf(".apk");
            if (p > 0) {
                s = s.substring(0, p);
            }
            return s.replace('-', '·').replace('_', '·');
        }

        /** 版本号 */
        public String version() {
            String[] m = match();
            if (m != null) {
                return m[2];
            }
            return "";
        }

        /** 对应游戏包名（用于取游戏图标、判断是否已安装） */
        public String pkg() {
            String[] m = match();
            if (m != null) {
                return m[3];
            }
            return "";
        }

        /** 保存到手机里的中文文件名 */
        public String fileName() {
            String l = label().replace('·', '-').replace('/', '-').replace('\\', '-');
            String v = version();
            return (v.length() == 0 ? l : (l + "-" + v)) + ".apk";
        }

        private String[] match() {
            String n = name == null ? "" : name.toLowerCase();
            for (String[] r : MAP) {
                if (n.contains(r[0])) {
                    return r;
                }
            }
            return null;
        }

        public String sizeText() {
            if (size <= 0) {
                return "";
            }
            double mb = size / 1048576.0;
            if (mb >= 1) {
                return String.format(java.util.Locale.US, "%.1f MB", mb);
            }
            return (size / 1024) + " KB";
        }
    }

    public interface Callback {
        void onResult(List<Item> items, String error);
    }

    /** 取对应游戏的桌面图标；游戏没装时返回 null，由界面用彩色圆标兜底 */
    public static Drawable icon(Context ctx, String pkg) {
        if (ctx == null || pkg == null || pkg.length() == 0) {
            return null;
        }
        try {
            PackageManager pm = ctx.getPackageManager();
            return pm.getApplicationIcon(pkg);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 用系统下载器下载，文件名用中文（中文名-版本.apk）。
     * 不用浏览器打开直链，因为那样存下来的就是一串英文字母。
     */
    public static void download(Context ctx, Item it) {
        try {
            if (it == null || it.url == null || it.url.length() == 0) {
                Logger.e("下载地址为空");
                return;
            }
            String fn = it.fileName();
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(it.url));
            req.setTitle("修仙脚本工具 · " + it.label());
            req.setDescription("正在下载 " + fn);
            req.setMimeType("application/vnd.android.package-archive");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fn);
            req.setAllowedOverRoaming(true);
            DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) {
                Logger.e("系统下载服务不可用");
                return;
            }
            dm.enqueue(req);
            Logger.i("已开始下载：" + fn);
        } catch (Throwable t) {
            Logger.e("下载失败: " + t);
            try {
                android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(it.url));
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 读取 Releases 清单，只保留游戏安装包（每个渠道取最新一份）。
     * 网络请求放子线程，回调切回主线程。
     */
    public static void fetch(final Callback cb) {
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<Item> out = new ArrayList<Item>();
                String err = null;
                HttpURLConnection conn = null;
                try {
                    String api = "https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases";
                    URL u = new URL(api);
                    conn = (HttpURLConnection) u.openConnection();
                    conn.setConnectTimeout(12000);
                    conn.setReadTimeout(15000);
                    conn.setRequestProperty("Accept", "application/vnd.github+json");
                    conn.setRequestProperty("User-Agent", "xiuxian-script");
                    int code = conn.getResponseCode();
                    if (code == 404) {
                        err = "仓库不存在或为私有（需登录才能读取）";
                    } else if (code != 200) {
                        err = "GitHub 返回 " + code;
                    } else {
                        InputStream is = conn.getInputStream();
                        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) {
                            sb.append(line);
                        }
                        br.close();
                        JSONArray arr = new JSONArray(sb.toString());
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject rel = arr.getJSONObject(i);
                            String tag = rel.optString("tag_name", "");
                            String title = rel.optString("name", "");
                            String pub = rel.optString("published_at", "");
                            if (pub.length() >= 10) {
                                pub = pub.substring(0, 10);
                            }
                            JSONArray assets = rel.optJSONArray("assets");
                            if (assets == null) {
                                continue;
                            }
                            for (int j = 0; j < assets.length(); j++) {
                                JSONObject a = assets.getJSONObject(j);
                                String nm = a.optString("name", "");
                                if (nm.toLowerCase().indexOf(".apk") < 0) {
                                    continue;
                                }
                                // 关键过滤：只留 MAP 里登记的游戏安装包，
                                // 脚本自身的构建产物（xiuxian-script.apk / default.apk）不显示
                                if (!isGameInstaller(nm)) {
                                    continue;
                                }
                                Item it = new Item();
                                it.tag = tag;
                                it.title = title;
                                it.name = nm;
                                it.url = a.optString("browser_download_url", "");
                                it.size = a.optLong("size", 0);
                                it.published = pub;
                                out.add(it);
                            }
                        }
                        // 按 MAP 顺序排好，同一渠道只保留第一份（Releases 按时间倒序，第一份即最新）
                        java.util.Collections.sort(out, new java.util.Comparator<Item>() {
                            @Override
                            public int compare(Item a, Item b) {
                                return mapIndex(a.name) - mapIndex(b.name);
                            }
                        });
                        List<Item> uniq = new ArrayList<Item>();
                        java.util.HashSet<String> seen = new java.util.HashSet<String>();
                        for (Item it : out) {
                            if (seen.add(it.label())) {
                                uniq.add(it);
                            }
                        }
                        out = uniq;
                    }
                } catch (Throwable t) {
                    err = "网络失败: " + t.getClass().getSimpleName();
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                    }
                }
                final List<Item> res = out;
                final String e = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onResult(res, e);
                    }
                });
            }
        }).start();
    }
}
