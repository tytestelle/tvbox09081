package com.github.tvbox.osc.util;

import android.content.res.AssetManager;
import android.text.TextUtils;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.EpgChannel;
import com.github.tvbox.osc.bean.EpgData;
import com.github.tvbox.osc.cache.EpgChannelDao;
import com.github.tvbox.osc.cache.EpgDataDao;
import com.github.tvbox.osc.data.AppDataManager;
import com.github.tvbox.osc.util.LOG;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * EPG工具类 - 内存缓存版（参考酷9优化）
 * 
 * 优化点：
 * 1. 启动时异步将所有频道映射加载到内存HashMap（~1-2MB），查询O(1)不卡顿
 * 2. EPG节目单缓存到数据库，避免重复网络请求
 * 3. 主线程只做内存查询，绝不碰数据库
 */
public class EpgUtil {
    private static final String TAG = "EpgUtil";
    private static final String EPG_DATA_JSON = "epg_data.json";
    private static final int BATCH_SIZE = 500;

    // 内存缓存：频道名 -> [logo, epgid]，查询O(1)，不卡顿
    private static final Map<String, String[]> memCache = new HashMap<>();
    private static volatile boolean cacheReady = false;

    // EPG节目单内存缓存（最近50个频道）
    private static final Map<String, ArrayList<com.github.tvbox.osc.bean.Epginfo>> epgMemCache = new HashMap<>();
    private static final int MAX_EPG_CACHE = 50;

    public static void init() {
        new Thread(() -> {
            loadEpgData();      // 导入JSON到数据库
            preloadMemCache();  // 加载到内存HashMap
        }).start();
    }

    /** 从assets导入epg_data.json到数据库（只执行一次） */
    private static void loadEpgData() {
        try {
            EpgChannelDao dao = AppDataManager.get().getEpgChannelDao();
            if (dao == null) return;
            if (dao.getCount() > 0) {
                LOG.i(TAG + " DB already has data");
                return;
            }
            AssetManager am = App.getInstance().getAssets();
            BufferedReader br = new BufferedReader(new InputStreamReader(am.open(EPG_DATA_JSON), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            if (sb.length() == 0) return;

            JsonObject root = JsonParser.parseString(sb.toString()).getAsJsonObject();
            if (root == null || !root.has("epgs")) return;
            JsonArray epgs = root.getAsJsonArray("epgs");
            List<EpgChannel> batch = new ArrayList<>();
            for (JsonElement el : epgs) {
                if (el == null || !el.isJsonObject()) continue;
                JsonObject obj = el.getAsJsonObject();
                String name = getString(obj, "name");
                String logo = getString(obj, "logo");
                String epgid = getString(obj, "epgid");
                if (TextUtils.isEmpty(name)) continue;
                for (String n : name.split(",")) {
                    String trim = n.trim();
                    if (TextUtils.isEmpty(trim)) continue;
                    EpgChannel ch = new EpgChannel();
                    ch.name = trim;
                    ch.logo = logo;
                    ch.epgid = epgid;
                    ch.aliases = name;
                    ch.updateTime = System.currentTimeMillis();
                    batch.add(ch);
                }
                if (batch.size() >= BATCH_SIZE) {
                    dao.insertAll(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) dao.insertAll(batch);
            LOG.i(TAG + " imported " + dao.getCount() + " channels");
        } catch (Exception e) {
            LOG.e(TAG + " load error: " + e.getMessage());
        }
    }

    /** 预加载所有频道映射到内存HashMap（~1-2MB，查询O(1)） */
    private static void preloadMemCache() {
        try {
            EpgChannelDao dao = AppDataManager.get().getEpgChannelDao();
            if (dao == null) return;
            List<EpgChannel> list = dao.getAll();
            if (list == null) return;
            synchronized (memCache) {
                for (EpgChannel ch : list) {
                    if (ch == null || ch.name == null) continue;
                    memCache.put(ch.name, new String[]{ch.logo, ch.epgid});
                }
                cacheReady = true;
            }
            LOG.i(TAG + " memCache loaded, size=" + memCache.size());
        } catch (Exception e) {
            LOG.e(TAG + " preload error: " + e.getMessage());
        }
    }

    /** 查台标和epgid - 只查内存，O(1)，不卡顿 */
    public static String[] getEpgInfo(String channelName) {
        if (TextUtils.isEmpty(channelName)) return null;
        synchronized (memCache) {
            String[] result = memCache.get(channelName);
            if (result != null) return result;
            // 模糊匹配：去掉空格横线
            String compact = channelName.replace("-", "").replace(" ", "").trim();
            if (!compact.equals(channelName)) {
                result = memCache.get(compact);
                if (result != null) return result;
            }
        }
        return null;
    }

    // ========== EPG节目单数据库缓存 ==========

    public static void saveEpgData(String channelName, String date, ArrayList<com.github.tvbox.osc.bean.Epginfo> list) {
        if (channelName == null || date == null || list == null || list.isEmpty()) return;
        // 同时更新内存缓存
        String key = channelName + "_" + date;
        synchronized (epgMemCache) {
            if (epgMemCache.size() >= MAX_EPG_CACHE) {
                // 清理一半
                ArrayList<String> keys = new ArrayList<>(epgMemCache.keySet());
                for (int i = 0; i < keys.size() / 2; i++) {
                    epgMemCache.remove(keys.get(i));
                }
            }
            epgMemCache.put(key, new ArrayList<>(list));
        }
        // 异步存数据库
        new Thread(() -> {
            try {
                EpgDataDao dao = AppDataManager.get().getEpgDataDao();
                if (dao == null) return;
                dao.delete(channelName, date);
                ArrayList<EpgData> dataList = new ArrayList<>();
                for (int i = 0; i < list.size(); i++) {
                    com.github.tvbox.osc.bean.Epginfo info = list.get(i);
                    if (info == null || info.startdateTime == null || info.enddateTime == null) continue;
                    EpgData d = new EpgData();
                    d.channelName = channelName;
                    d.date = date;
                    d.title = info.title;
                    d.start = info.start;
                    d.end = info.end;
                    d.startTime = info.startdateTime.getTime();
                    d.endTime = info.enddateTime.getTime();
                    d.idx = i;
                    dataList.add(d);
                }
                dao.insertAll(dataList);
            } catch (Exception e) {
                LOG.e(TAG + " saveEpg error: " + e.getMessage());
            }
        }).start();
    }

    public static ArrayList<com.github.tvbox.osc.bean.Epginfo> loadEpgData(String channelName, String date, Date baseDate) {
        ArrayList<com.github.tvbox.osc.bean.Epginfo> result = new ArrayList<>();
        if (channelName == null || date == null) return result;
        // 先查内存
        String key = channelName + "_" + date;
        synchronized (epgMemCache) {
            ArrayList<com.github.tvbox.osc.bean.Epginfo> cached = epgMemCache.get(key);
            if (cached != null) return new ArrayList<>(cached);
        }
        // 再查数据库
        try {
            EpgDataDao dao = AppDataManager.get().getEpgDataDao();
            if (dao == null) return result;
            List<EpgData> list = dao.get(channelName, date);
            if (list == null || list.isEmpty()) return result;
            java.text.SimpleDateFormat tf = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault());
            for (EpgData d : list) {
                if (d == null) continue;
                com.github.tvbox.osc.bean.Epginfo info = new com.github.tvbox.osc.bean.Epginfo(baseDate, d.title, baseDate, d.start, d.end, d.idx);
                info.startdateTime = new Date(d.startTime);
                info.enddateTime = new Date(d.endTime);
                info.start = d.start;
                info.end = d.end;
                info.datestart = Integer.parseInt(d.start.replace(":", ""));
                info.dateend = Integer.parseInt(d.end.replace(":", ""));
                result.add(info);
            }
            // 放入内存缓存
            synchronized (epgMemCache) {
                if (epgMemCache.size() < MAX_EPG_CACHE) {
                    epgMemCache.put(key, new ArrayList<>(result));
                }
            }
        } catch (Exception e) {
            LOG.e(TAG + " loadEpg error: " + e.getMessage());
        }
        return result;
    }

    public static boolean hasEpgData(String channelName, String date) {
        if (channelName == null || date == null) return false;
        String key = channelName + "_" + date;
        synchronized (epgMemCache) {
            if (epgMemCache.containsKey(key)) return true;
        }
        try {
            EpgDataDao dao = AppDataManager.get().getEpgDataDao();
            if (dao == null) return false;
            return dao.count(channelName, date) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static void clearEpgData() {
        synchronized (epgMemCache) {
            epgMemCache.clear();
        }
        new Thread(() -> {
            try {
                EpgDataDao dao = AppDataManager.get().getEpgDataDao();
                if (dao == null) return;
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault());
                java.util.Calendar cal = java.util.Calendar.getInstance();
                cal.add(java.util.Calendar.DAY_OF_MONTH, -7);
                dao.deleteExpired(sdf.format(cal.getTime()));
            } catch (Exception e) {
                LOG.e(TAG + " clearEpg error: " + e.getMessage());
            }
        }).start();
    }

    private static String getString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return "";
        try {
            return obj.get(key).getAsString();
        } catch (Exception e) {
            return "";
        }
    }
}
