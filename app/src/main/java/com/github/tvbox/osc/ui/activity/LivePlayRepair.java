package com.github.tvbox.osc.ui.activity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.text.TextUtils;

import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import java.util.ArrayList;
import java.util.List;

/**
 * LivePlayActivity 直播源加载修复模块
 * 插入到 LivePlayActivity 类中，替换原有的直播源初始化逻辑
 */
public class LivePlayRepair {

    /**
     * 注册广播接收器（在LivePlayActivity.onCreate中调用）
     */
    public static void registerRefreshReceiver(BaseActivity activity) {
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.github.tvbox.osc.LIVE_REFRESH");
        filter.addAction("com.github.tvbox.osc.EPG_REFRESH");
        activity.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if ("com.github.tvbox.osc.LIVE_REFRESH".equals(action)) {
                    // 重新加载直播源
                    loadLiveSource(activity);
                } else if ("com.github.tvbox.osc.EPG_REFRESH".equals(action)) {
                    // 重新加载EPG
                    loadEpgData(activity);
                }
            }
        }, filter);
    }

    /**
     * 加载直播订阅源（替换原有的默认源加载）
     */
    public static void loadLiveSource(Context context) {
        String liveUrl = Hawk.get(HawkConfig.LIVE_API_URL, "");
        if (!TextUtils.isEmpty(liveUrl)) {
            // 从订阅地址加载
            loadLiveFromUrl(context, liveUrl);
        } else {
            // 加载默认本地源或内置源
            loadDefaultLiveSource(context);
        }
    }

    /**
     * 从URL加载直播源（m3u/txt格式）
     */
    private static void loadLiveFromUrl(Context context, String url) {
        new Thread(() -> {
            try {
                // 使用OkHttp请求订阅地址
                okhttp3.Request request = new okhttp3.Request.Builder()
                        .url(url)
                        .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .build();
                okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
                okhttp3.Response response = client.newCall(request).execute();
                if (response.isSuccessful() && response.body() != null) {
                    String content = response.body().string();
                    parseLiveContent(content);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    /**
     * 解析直播源内容（支持M3U和TXT格式）
     */
    private static void parseLiveContent(String content) {
        List<LiveChannel> channels = new ArrayList<>();
        if (content.contains("#EXTM3U")) {
            // M3U格式解析
            parseM3u(content, channels);
        } else {
            // TXT格式解析（分组格式）
            parseTxt(content, channels);
        }
        // 保存到内存或数据库，通知UI刷新
        LiveDataHolder.setChannels(channels);
    }

    /**
     * 解析M3U格式
     */
    private static void parseM3u(String content, List<LiveChannel> list) {
        String[] lines = content.split("\n");
        String currentGroup = "默认分组";
        String currentName = "";
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("#EXTINF")) {
                // 提取频道名称和分组
                int tvgNameIdx = line.indexOf("tvg-name=\"");
                int groupIdx = line.indexOf("group-title=\"");
                if (tvgNameIdx > 0) {
                    int start = tvgNameIdx + 10;
                    int end = line.indexOf("\"", start);
                    currentName = line.substring(start, end);
                }
                if (groupIdx > 0) {
                    int start = groupIdx + 13;
                    int end = line.indexOf("\"", start);
                    currentGroup = line.substring(start, end);
                }
                // 从逗号后取名称（备用）
                if (currentName.isEmpty()) {
                    int comma = line.lastIndexOf(",");
                    if (comma > 0) currentName = line.substring(comma + 1).trim();
                }
            } else if (!line.startsWith("#") && !line.isEmpty() && line.contains("://")) {
                LiveChannel ch = new LiveChannel();
                ch.name = currentName.isEmpty() ? "未知频道" : currentName;
                ch.url = line;
                ch.group = currentGroup;
                list.add(ch);
            }
        }
    }

    /**
     * 解析TXT格式（分组,#genre#格式）
     */
    private static void parseTxt(String content, List<LiveChannel> list) {
        String[] lines = content.split("\n");
        String currentGroup = "默认分组";
        for (String line : lines) {
            line = line.trim();
            if (line.contains(",#genre#")) {
                currentGroup = line.split(",")[0].trim();
            } else if (line.contains(",") && !line.startsWith("#")) {
                String[] parts = line.split(",", 2);
                if (parts.length == 2 && parts[1].contains("://")) {
                    LiveChannel ch = new LiveChannel();
                    ch.name = parts[0].trim();
                    ch.url = parts[1].trim();
                    ch.group = currentGroup;
                    list.add(ch);
                }
            }
        }
    }

    /**
     * 加载默认直播源
     */
    private static void loadDefaultLiveSource(Context context) {
        // 保留原有默认源加载逻辑
    }

    /**
     * 加载EPG数据
     */
    public static void loadEpgData(Context context) {
        String epgUrl = Hawk.get(HawkConfig.EPG_URL, "");
        if (!TextUtils.isEmpty(epgUrl)) {
            // 加载EPG XML数据
            new Thread(() -> {
                try {
                    okhttp3.Request request = new okhttp3.Request.Builder()
                            .url(epgUrl)
                            .build();
                    okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
                    okhttp3.Response response = client.newCall(request).execute();
                    if (response.isSuccessful() && response.body() != null) {
                        String xml = response.body().string();
                        // 解析XML EPG数据
                        EpgDataHolder.parse(xml);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();
        }
    }

    /**
     * 直播频道数据类
     */
    public static class LiveChannel {
        public String name;
        public String url;
        public String group;
        public String logo;
    }

    /**
     * 直播数据持有者（单例）
     */
    public static class LiveDataHolder {
        private static List<LiveChannel> channels = new ArrayList<>();
        public static void setChannels(List<LiveChannel> list) {
            channels = list;
        }
        public static List<LiveChannel> getChannels() {
            return channels;
        }
    }

    /**
     * EPG数据持有者
     */
    public static class EpgDataHolder {
        public static void parse(String xml) {
            // EPG XML解析实现
        }
    }
}
