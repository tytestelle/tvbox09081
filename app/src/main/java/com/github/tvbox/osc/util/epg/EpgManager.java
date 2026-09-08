package com.github.tvbox.osc.util.epg;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.text.TextUtils;

import com.github.tvbox.osc.util.FileLogger;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class EpgManager {
    private static final String DB_NAME = "epg_cache.db";
    private static final int DB_VERSION = 1;
    private static EpgManager instance;
    private Context context;
    private SQLiteDatabase db;
    private OkHttpClient httpClient;
    private String epgUrl;

    // epg_data.json 映射：epgid -> 逗号分隔的名称列表
    private Map<String, String> epgDataMap = null;

    public static synchronized EpgManager getInstance(Context context) {
        if (instance == null) instance = new EpgManager(context.getApplicationContext());
        return instance;
    }

    private EpgManager(Context context) {
        this.context = context;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
        DBHelper helper = new DBHelper(context);
        db = helper.getWritableDatabase();
        epgUrl = EpgSettings.getEpgUrl(context);
        if (TextUtils.isEmpty(epgUrl)) loadDefaultEpgUrl();
        loadEpgDataMap(); // 加载 epg_data.json 到内存
    }

    // ======================== 加载 epg_data.json ========================
    private void loadEpgDataMap() {
        epgDataMap = new HashMap<>();
        try {
            InputStream is = context.getAssets().open("epg_data.json");
            JsonObject root = JsonParser.parseReader(new InputStreamReader(is)).getAsJsonObject();
            JsonArray epgs = root.getAsJsonArray("epgs");
            for (int i = 0; i < epgs.size(); i++) {
                JsonObject item = epgs.get(i).getAsJsonObject();
                String epgid = item.get("epgid").getAsString();
                String name = item.get("name").getAsString();
                epgDataMap.put(epgid, name);
                FileLogger.write("EpgManager", "加载映射: " + epgid + " -> " + name);
            }
            FileLogger.write("EpgManager", "加载 epg_data.json 成功，共 " + epgDataMap.size() + " 条");
        } catch (Exception e) {
            FileLogger.write("EpgManager", "加载 epg_data.json 失败", e);
            // 使用空映射，后续匹配将返回 null
        }
    }

    // ======================== 核心匹配逻辑 ========================
    /**
     * 根据频道名（可能包含 "HD"、"高清" 等后缀）在 epg_data.json 的 name 列表中
     * 进行包含匹配，返回对应的 epgid。
     */
    private String getEpgIdByChannelName(String channelName) {
        if (TextUtils.isEmpty(channelName) || epgDataMap == null || epgDataMap.isEmpty()) {
            return null;
        }
        String trimmed = channelName.trim();
        // 1. 精确匹配（忽略大小写）
        for (Map.Entry<String, String> entry : epgDataMap.entrySet()) {
            String epgId = entry.getKey();
            String nameList = entry.getValue();
            if (TextUtils.isEmpty(nameList)) continue;
            String[] names = nameList.split(",");
            for (String name : names) {
                name = name.trim();
                if (name.isEmpty()) continue;
                if (trimmed.equalsIgnoreCase(name)) {
                    FileLogger.write("EpgManager", "精确匹配: " + trimmed + " -> " + epgId);
                    return epgId;
                }
            }
        }

        // 2. 包含匹配（频道名包含在某个 name 中，或 name 包含在频道名中）
        for (Map.Entry<String, String> entry : epgDataMap.entrySet()) {
            String epgId = entry.getKey();
            String nameList = entry.getValue();
            String[] names = nameList.split(",");
            for (String name : names) {
                name = name.trim();
                if (name.isEmpty()) continue;
                if (trimmed.contains(name) || name.contains(trimmed)) {
                    FileLogger.write("EpgManager", "包含匹配: " + trimmed + " -> " + epgId + " (命中: " + name + ")");
                    return epgId;
                }
            }
        }
        FileLogger.write("EpgManager", "未找到匹配的 epgId: " + trimmed);
        return null;
    }

    // ======================== 加载默认 EPG URL ========================
    private void loadDefaultEpgUrl() {
        try {
            InputStream is = context.getAssets().open("configuration.json");
            JsonObject config = JsonParser.parseReader(new InputStreamReader(is)).getAsJsonObject();
            String epgUrls = config.getAsJsonObject("Configuration").get("EPG_URLS").getAsString();
            if (!TextUtils.isEmpty(epgUrls)) {
                String[] parts = epgUrls.split("\\|\\|");
                for (String part : parts) {
                    String trimmed = part.trim();
                    if (trimmed.isEmpty()) continue;
                    int idx = trimmed.lastIndexOf('$');
                    String url = idx > 0 ? trimmed.substring(0, idx).trim() : trimmed;
                    if (!TextUtils.isEmpty(url)) {
                        epgUrl = url;
                        EpgSettings.saveEpgUrl(context, epgUrl);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            FileLogger.write("EpgManager", "加载默认 EPG URL 失败", e);
        }
    }

    // ======================== 刷新 EPG ========================
    public void refreshEpg(RefreshCallback callback) {
        if (TextUtils.isEmpty(epgUrl)) {
            if (callback != null) callback.onError("EPG URL 未设置");
            return;
        }
        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... voids) {
                try {
                    Request request = new Request.Builder().url(epgUrl).build();
                    Response response = httpClient.newCall(request).execute();
                    if (!response.isSuccessful()) {
                        FileLogger.write("EpgManager", "下载失败 HTTP=" + response.code());
                        return false;
                    }
                    String xml = response.body().string();
                    return parseAndStore(xml);
                } catch (Exception e) {
                    FileLogger.write("EpgManager", "下载异常", e);
                    return false;
                }
            }

            @Override
            protected void onPostExecute(Boolean success) {
                if (callback != null) {
                    if (success) {
                        callback.onSuccess();
                        FileLogger.write("EpgManager", "EPG 刷新成功");
                    } else {
                        callback.onError("解析失败");
                        FileLogger.write("EpgManager", "EPG 刷新失败");
                    }
                }
            }
        }.execute();
    }

    // ======================== 解析 XML 并存储 ========================
    private boolean parseAndStore(String xml) {
        db.delete("programs", null, null);
        db.delete("channels", null, null);
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new ByteArrayInputStream(xml.getBytes("UTF-8")), null);

            int eventType = parser.getEventType();
            String currentChannelId = null;
            String currentDisplayName = null;
            String currentIcon = null;
            String currentStart = null;
            String currentStop = null;
            String currentTitle = null;
            String currentDesc = null;
            String currentChannelIdForProgram = null;

            ContentValues channelValues = new ContentValues();
            ContentValues programValues = new ContentValues();

            while (eventType != XmlPullParser.END_DOCUMENT) {
                String tagName = parser.getName();
                switch (eventType) {
                    case XmlPullParser.START_TAG:
                        if ("channel".equals(tagName)) {
                            currentChannelId = parser.getAttributeValue(null, "id");
                            currentDisplayName = null;
                            currentIcon = null;
                            FileLogger.write("EpgManager", "开始解析 channel id: " + currentChannelId);
                        } else if ("display-name".equals(tagName) && currentChannelId != null) {
                            currentDisplayName = parser.nextText();
                            FileLogger.write("EpgManager", "提取 display-name: " + currentDisplayName + " (channel id: " + currentChannelId + ")");
                        } else if ("icon".equals(tagName) && currentChannelId != null) {
                            currentIcon = parser.getAttributeValue(null, "src");
                            FileLogger.write("EpgManager", "提取 icon: " + currentIcon + " (channel id: " + currentChannelId + ")");
                        } else if ("programme".equals(tagName)) {
                            currentChannelIdForProgram = parser.getAttributeValue(null, "channel");
                            currentStart = parser.getAttributeValue(null, "start");
                            currentStop = parser.getAttributeValue(null, "stop");
                            currentTitle = null;
                            currentDesc = null;
                        } else if ("title".equals(tagName)) {
                            currentTitle = parser.nextText();
                        } else if ("desc".equals(tagName)) {
                            currentDesc = parser.nextText();
                        }
                        break;
                    case XmlPullParser.END_TAG:
                        if ("channel".equals(tagName)) {
                            if (currentChannelId != null && currentDisplayName != null) {
                                channelValues.clear();
                                channelValues.put("channel_id", currentChannelId);
                                channelValues.put("display_name", currentDisplayName);
                                if (currentIcon != null) channelValues.put("icon", currentIcon);
                                long result = db.insert("channels", null, channelValues);
                                FileLogger.write("EpgManager", "插入频道: " + currentDisplayName + " id=" + currentChannelId + " 结果=" + result);
                            } else {
                                FileLogger.write("EpgManager", "频道数据不完整: id=" + currentChannelId + ", display_name=" + currentDisplayName);
                            }
                            // 重置
                            currentChannelId = null;
                            currentDisplayName = null;
                            currentIcon = null;
                        } else if ("programme".equals(tagName)) {
                            if (currentChannelIdForProgram != null && currentStart != null && currentStop != null) {
                                programValues.clear();
                                programValues.put("channel_id", currentChannelIdForProgram);
                                programValues.put("start_time", currentStart);
                                programValues.put("stop_time", currentStop);
                                programValues.put("title", currentTitle != null ? currentTitle : "");
                                programValues.put("desc", currentDesc != null ? currentDesc : "");
                                long result = db.insert("programs", null, programValues);
                                if (result == -1) {
                                    FileLogger.write("EpgManager", "插入节目失败: channel=" + currentChannelIdForProgram + " start=" + currentStart);
                                }
                            } else {
                                FileLogger.write("EpgManager", "节目数据不完整: channel=" + currentChannelIdForProgram + ", start=" + currentStart + ", stop=" + currentStop);
                            }
                            // 重置
                            currentChannelIdForProgram = null;
                            currentStart = null;
                            currentStop = null;
                            currentTitle = null;
                            currentDesc = null;
                        }
                        break;
                }
                eventType = parser.next();
            }
            // 打印所有已插入的频道，用于调试
            Cursor debug = db.query("channels", new String[]{"channel_id", "display_name"}, null, null, null, null, null);
            while (debug.moveToNext()) {
                FileLogger.write("EpgManager", "DB频道: id=" + debug.getString(0) + ", display_name=" + debug.getString(1));
            }
            debug.close();
            FileLogger.write("EpgManager", "解析完成，节目数: " + getProgramCount());
            return true;
        } catch (Exception e) {
            FileLogger.write("EpgManager", "解析异常", e);
            return false;
        }
    }

    private int getProgramCount() {
        Cursor c = db.query("programs", new String[]{"COUNT(*)"}, null, null, null, null, null);
        int count = 0;
        if (c.moveToFirst()) count = c.getInt(0);
        c.close();
        return count;
    }

    // ======================== 查询节目 ========================
    public List<EpgProgram> getProgramsForChannel(String channelName) {
        List<EpgProgram> programs = new ArrayList<>();
        // 1. 获取 epgId
        String epgId = getEpgIdByChannelName(channelName);
        if (epgId == null) {
            FileLogger.write("EpgManager", "未找到 epgId: " + channelName);
            return programs;
        }
        FileLogger.write("EpgManager", "频道: " + channelName + " -> epgId: " + epgId);

        // 2. 用 epgId 作为 display_name 查询 channel_id
        String channelId = null;
        String iconUrl = null;
        Cursor c = db.query("channels", new String[]{"channel_id", "icon"}, "display_name=?", new String[]{epgId}, null, null, null);
        if (c.moveToFirst()) {
            channelId = c.getString(0);
            iconUrl = c.getString(1);
            FileLogger.write("EpgManager", "通过 display_name 找到 channel_id: " + channelId);
        }
        c.close();

        // 如果找不到，尝试用 epgId 作为 channel_id 直接查询
        if (channelId == null) {
            Cursor c2 = db.query("channels", new String[]{"channel_id", "icon"}, "channel_id=?", new String[]{epgId}, null, null, null);
            if (c2.moveToFirst()) {
                channelId = c2.getString(0);
                iconUrl = c2.getString(1);
                FileLogger.write("EpgManager", "通过 channel_id 找到: " + epgId);
            }
            c2.close();
        }

        if (channelId == null) {
            FileLogger.write("EpgManager", "未在 EPG 中找到 display_name 或 channel_id: " + epgId);
            // 调试：打印所有 display_name
            Cursor debug = db.query("channels", new String[]{"display_name"}, null, null, null, null, null);
            while (debug.moveToNext()) {
                FileLogger.write("EpgManager", "DB中的 display_name: " + debug.getString(0));
            }
            debug.close();
            return programs;
        }

        FileLogger.write("EpgManager", "找到 channel_id: " + channelId);

        // 3. 如果有图标 URL，下载并透明化保存为 epgid.png
        if (!TextUtils.isEmpty(iconUrl)) {
            downloadAndProcessIcon(epgId, iconUrl);
        }

        // 4. 查询节目
        Cursor cur = db.query("programs", null, "channel_id=?", new String[]{channelId}, null, null, "start_time ASC");
        while (cur.moveToNext()) {
            String start = cur.getString(cur.getColumnIndex("start_time"));
            String stop = cur.getString(cur.getColumnIndex("stop_time"));
            String title = cur.getString(cur.getColumnIndex("title"));
            String desc = cur.getString(cur.getColumnIndex("desc"));
            programs.add(new EpgProgram(title, desc, parseXmltvTime(start), parseXmltvTime(stop)));
        }
        cur.close();
        FileLogger.write("EpgManager", "找到 " + programs.size() + " 个节目");
        return programs;
    }

    public EpgProgram getCurrentProgram(String channelName) {
        List<EpgProgram> list = getProgramsForChannel(channelName);
        Date now = new Date();
        for (EpgProgram p : list) {
            if (p.start.before(now) && p.stop.after(now)) return p;
        }
        return null;
    }

    public EpgProgram getNextProgram(String channelName) {
        List<EpgProgram> list = getProgramsForChannel(channelName);
        Date now = new Date();
        for (EpgProgram p : list) {
            if (p.start.after(now)) return p;
        }
        return null;
    }

    // ======================== 台标处理（透明化保存为 epgid.png） ========================
    private void downloadAndProcessIcon(String epgId, String iconUrl) {
        if (TextUtils.isEmpty(epgId) || TextUtils.isEmpty(iconUrl)) return;
        File iconFile = new File(context.getCacheDir(), epgId + ".png");
        if (iconFile.exists()) {
            FileLogger.write("EpgManager", "台标已存在: " + iconFile.getAbsolutePath());
            return;
        }

        try {
            Request request = new Request.Builder().url(iconUrl).build();
            Response response = httpClient.newCall(request).execute();
            if (!response.isSuccessful()) {
                FileLogger.write("EpgManager", "台标下载失败 HTTP=" + response.code());
                return;
            }
            Bitmap bitmap = BitmapFactory.decodeStream(response.body().byteStream());
            if (bitmap == null) {
                FileLogger.write("EpgManager", "台标解码失败");
                return;
            }

            // 透明化处理：将白色背景转为透明
            Bitmap processed = makeTransparent(bitmap);
            FileOutputStream fos = new FileOutputStream(iconFile);
            processed.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();
            FileLogger.write("EpgManager", "台标已保存: " + iconFile.getAbsolutePath());
        } catch (Exception e) {
            FileLogger.write("EpgManager", "台标下载/处理异常", e);
        }
    }

    private Bitmap makeTransparent(Bitmap src) {
        // 简单示例：将白色 (RGB 接近 255,255,255) 设置为透明
        int width = src.getWidth();
        int height = src.getHeight();
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[width * height];
        src.getPixels(pixels, 0, width, 0, 0, width, height);

        for (int i = 0; i < pixels.length; i++) {
            int color = pixels[i];
            int r = (color >> 16) & 0xFF;
            int g = (color >> 8) & 0xFF;
            int b = color & 0xFF;
            if (r > 240 && g > 240 && b > 240) {
                pixels[i] = 0x00000000; // 完全透明
            }
        }
        result.setPixels(pixels, 0, width, 0, 0, width, height);
        return result;
    }

    // ======================== 工具方法 ========================
    private Date parseXmltvTime(String time) {
        try {
            if (time.length() >= 14) {
                int year = Integer.parseInt(time.substring(0, 4));
                int month = Integer.parseInt(time.substring(4, 6));
                int day = Integer.parseInt(time.substring(6, 8));
                int hour = Integer.parseInt(time.substring(8, 10));
                int minute = Integer.parseInt(time.substring(10, 12));
                int second = Integer.parseInt(time.substring(12, 14));
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                sdf.setTimeZone(TimeZone.getTimeZone("GMT+8"));
                return sdf.parse(year + "-" + month + "-" + day + " " + hour + ":" + minute + ":" + second);
            }
        } catch (Exception e) {
            FileLogger.write("EpgManager", "时间解析失败: " + time);
        }
        return new Date();
    }

    // ======================== 接口与内部类 ========================
    public interface RefreshCallback {
        void onSuccess();
        void onError(String msg);
    }

    public static class EpgProgram {
        public String title, description;
        public Date start, stop;
        public EpgProgram(String t, String d, Date s, Date e) {
            title = t;
            description = d;
            start = s;
            stop = e;
        }
    }

    private static class DBHelper extends SQLiteOpenHelper {
        public DBHelper(Context context) {
            super(context, DB_NAME, null, DB_VERSION);
        }
        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE channels (channel_id TEXT PRIMARY KEY, display_name TEXT, icon TEXT)");
            db.execSQL("CREATE TABLE programs (id INTEGER PRIMARY KEY AUTOINCREMENT, channel_id TEXT, start_time TEXT, stop_time TEXT, title TEXT, desc TEXT)");
        }
        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}
    }
}
