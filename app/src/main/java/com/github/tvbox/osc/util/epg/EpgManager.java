package com.github.tvbox.osc.util.epg;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.AsyncTask;
import android.text.TextUtils;

import com.github.tvbox.osc.util.FileLogger;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * XMLTV EPG manager.
 *
 * EPG source of truth is the downloaded XMLTV file in files/epgche/epg.xml.
 * The remote <epg-url>.hash is checked first. Only when the hash changes (or
 * the local XML is missing) is the XML downloaded again. Playback never waits
 * for EPG network I/O: refresh and icon downloads run in background threads.
 */
public class EpgManager {
    private static final String TAG = "EpgManager";
    private static final String EPG_DIR_NAME = "epgche";
    private static final String EPG_FILE_NAME = "epg.xml";
    private static final String HASH_FILE_NAME = "epg.hash";
    private static final String LOGO_DIR_NAME = "logos";

    private static EpgManager instance;

    private final Context context;
    private final OkHttpClient httpClient;
    private final ExecutorService iconExecutor = Executors.newFixedThreadPool(2);
    private final ExecutorService epgExecutor = Executors.newSingleThreadExecutor();
    private final Set<String> iconInFlight = Collections.synchronizedSet(new HashSet<>());
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final File epgDir;
    private final File epgFile;
    private final File localHashFile;
    private final File logoDir;

    private volatile String epgUrl;
    private volatile boolean parsing;
    private volatile boolean parsed;
    private volatile boolean refreshRunning;
    private final List<RefreshCallback> pendingRefreshCallbacks = new ArrayList<>();

    // epg_data.json: every variant name maps to one shared epgid.
    private final Map<String, String> nameToEpgId = new HashMap<>();

    // Parsed XMLTV indexes. Access is synchronized through parseLock.
    private final Object parseLock = new Object();
    private final Map<String, ChannelInfo> channelsByDisplayName = new HashMap<>();
    private final Map<String, ChannelInfo> channelsById = new HashMap<>();
    private final Map<String, List<EpgProgram>> programsByChannelId = new HashMap<>();
    private final Map<String, List<String>> xmlChannelIdsByEpgId = new HashMap<>();
    private final Map<String, List<EpgProgram>> programsByEpgId = new HashMap<>();
    private final Map<String, String> iconUrlByEpgId = new HashMap<>();
    private final Set<String> loadedEpgIds = new HashSet<>();
    private static final String LOGO_PREFS = "logo_settings";
    private static final String LOGO_SOURCE_KEY = "xmltv_logo_source";
    public static final String LOGO_SOURCE_EPG = "EPG";
    public static final String LOGO_SOURCE_GITHUB = "GITHUB";

    public static String getLogoSource(Context context) {
        if (context == null) return LOGO_SOURCE_EPG;
        String value = context.getSharedPreferences(LOGO_PREFS, Context.MODE_PRIVATE)
                .getString(LOGO_SOURCE_KEY, LOGO_SOURCE_EPG);
        return LOGO_SOURCE_GITHUB.equals(value) ? LOGO_SOURCE_GITHUB : LOGO_SOURCE_EPG;
    }

    public static void setLogoSource(Context context, String source) {
        if (context == null) return;
        String value = LOGO_SOURCE_GITHUB.equals(source) ? LOGO_SOURCE_GITHUB : LOGO_SOURCE_EPG;
        context.getSharedPreferences(LOGO_PREFS, Context.MODE_PRIVATE)
                .edit().putString(LOGO_SOURCE_KEY, value).apply();
    }

    public static synchronized EpgManager getInstance(Context context) {
        if (instance == null) instance = new EpgManager(context.getApplicationContext());
        return instance;
    }

    private EpgManager(Context context) {
        this.context = context;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .build();

        epgDir = new File(context.getFilesDir(), EPG_DIR_NAME);
        if (!epgDir.exists()) epgDir.mkdirs();
        epgFile = new File(epgDir, EPG_FILE_NAME);
        localHashFile = new File(epgDir, HASH_FILE_NAME);
        logoDir = new File(context.getFilesDir(), LOGO_DIR_NAME);
        if (!logoDir.exists()) logoDir.mkdirs();

        epgUrl = normalizeEpgUrl(EpgSettings.getEpgUrl(context));
        if (TextUtils.isEmpty(epgUrl)) loadDefaultEpgUrl();
        loadEpgDataMap();
    }

    // ======================== epg_data.json ========================
    private void loadEpgDataMap() {
        synchronized (nameToEpgId) {
            nameToEpgId.clear();
            try (InputStream is = context.getAssets().open("epg_data.json")) {
                JsonObject root = JsonParser.parseReader(new InputStreamReader(is, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonArray epgs = root.getAsJsonArray("epgs");
                if (epgs == null) return;
                for (int i = 0; i < epgs.size(); i++) {
                    JsonObject item = epgs.get(i).getAsJsonObject();
                    if (!item.has("epgid") || !item.has("name")) continue;
                    String epgid = item.get("epgid").getAsString().trim();
                    String names = item.get("name").getAsString();
                    if (epgid.isEmpty() || names == null) continue;
                    for (String variant : names.split(",")) {
                        String key = variant == null ? "" : variant.trim();
                        if (!key.isEmpty()) nameToEpgId.put(key, epgid);
                    }
                }
                FileLogger.write(TAG, "加载 epg_data.json 成功，共 " + nameToEpgId.size() + " 个频道变种");
            } catch (Exception e) {
                FileLogger.write(TAG, "加载 epg_data.json 失败", e);
            }
        }
    }

    private String getEpgIdByChannelName(String channelName) {
        if (TextUtils.isEmpty(channelName)) return null;
        synchronized (nameToEpgId) {
            return nameToEpgId.get(channelName);
        }
    }

    public String resolveEpgId(String originalChannelName) {
        return getEpgIdByChannelName(originalChannelName);
    }

    public synchronized void setEpgUrl(String url) {
        epgUrl = normalizeEpgUrl(url);
        if (!TextUtils.isEmpty(epgUrl)) EpgSettings.saveEpgUrl(context, epgUrl);
    }

    private String normalizeEpgUrl(String url) {
        if (TextUtils.isEmpty(url)) return "";
        String value = url.trim();
        int suffix = value.indexOf('$');
        if (suffix > 0) value = value.substring(0, suffix).trim();
        return value;
    }

    // ======================== 默认 EPG URL ========================
    private void loadDefaultEpgUrl() {
        try (InputStream is = context.getAssets().open("configuration.json")) {
            JsonObject config = JsonParser.parseReader(new InputStreamReader(is, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject configuration = config.getAsJsonObject("Configuration");
            if (configuration == null || !configuration.has("EPG_URLS")) return;
            String epgUrls = configuration.get("EPG_URLS").getAsString();
            if (TextUtils.isEmpty(epgUrls)) return;
            for (String part : epgUrls.split("\\|\\|")) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) continue;
                int idx = trimmed.lastIndexOf('$');
                String url = idx > 0 ? trimmed.substring(0, idx).trim() : trimmed;
                if (!url.isEmpty()) {
                    setEpgUrl(url);
                    return;
                }
            }
        } catch (Exception e) {
            FileLogger.write(TAG, "加载默认 EPG URL 失败", e);
        }
    }

    // ======================== HASH + 下载 ========================
    public void refreshEpg(RefreshCallback callback) {
        final String url = epgUrl;
        if (TextUtils.isEmpty(url)) {
            if (callback != null) callback.onError("EPG URL 未设置");
            return;
        }
        synchronized (this) {
            if (callback != null) pendingRefreshCallbacks.add(callback);
            if (refreshRunning) return;
            refreshRunning = true;
        }
        new AsyncTask<Void, Void, RefreshResult>() {
            @Override protected RefreshResult doInBackground(Void... ignored) {
                return refreshFromHash(url);
            }

            @Override protected void onPostExecute(RefreshResult result) {
                List<RefreshCallback> callbacks;
                synchronized (EpgManager.this) {
                    refreshRunning = false;
                    callbacks = new ArrayList<>(pendingRefreshCallbacks);
                    pendingRefreshCallbacks.clear();
                }
                for (RefreshCallback cb : callbacks) {
                    try {
                        if (result.success) cb.onSuccess();
                        else cb.onError(result.message);
                    } catch (Exception ignored) { }
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private RefreshResult refreshFromHash(String url) {
        try {
            if (!epgDir.exists() && !epgDir.mkdirs()) {
                return RefreshResult.error("无法创建 EPG 缓存目录");
            }

            final String hashUrl = url + ".hash";
            FileLogger.write(TAG, "EPG检查开始: url=" + url + ", hash=" + hashUrl);
            String remoteHash = fetchRemoteHash(hashUrl);
            String localHash = readText(localHashFile);
            FileLogger.write(TAG, "EPG hash: remote=" + (TextUtils.isEmpty(remoteHash) ? "<empty>" : remoteHash) + ", local=" + (TextUtils.isEmpty(localHash) ? "<empty>" : localHash));

            if (!TextUtils.isEmpty(remoteHash) && epgFile.exists() && remoteHash.equals(localHash)) {
                FileLogger.write(TAG, "EPG hash 未变化，直接使用本地文件（不解析全量 XML）: " + epgFile.getAbsolutePath());
                return RefreshResult.ok();
            }

            if (TextUtils.isEmpty(remoteHash) && epgFile.exists()) {
                FileLogger.write(TAG, "EPG hash 获取失败，继续使用已有 EPG 本地文件（不全量解析）");
                return RefreshResult.ok();
            }

            FileLogger.write(TAG, "EPG hash 已变化或本地文件不存在，开始下载 XMLTV: " + url);
            File tmp = new File(epgDir, EPG_FILE_NAME + ".tmp");
            downloadXmlToFile(url, tmp);
            if (!tmp.exists() || tmp.length() == 0) return RefreshResult.error("EPG 文件下载为空");

            if (!tmp.renameTo(epgFile)) {
                copyFile(tmp, epgFile);
                tmp.delete();
            }
            if (!TextUtils.isEmpty(remoteHash)) writeTextAtomically(localHashFile, remoteHash);

            synchronized (parseLock) {
                channelsByDisplayName.clear();
                channelsById.clear();
                programsByChannelId.clear();
                programsByEpgId.clear();
                xmlChannelIdsByEpgId.clear();
                iconUrlByEpgId.clear();
                loadedEpgIds.clear();
                parsed = false;
            }
            FileLogger.write(TAG, "EPG 下载成功，等待按频道组懒加载: " + epgFile.getAbsolutePath());
            return RefreshResult.ok();
        } catch (Exception e) {
            FileLogger.write(TAG, "EPG 刷新异常", e);
            if (epgFile.exists()) return ensureParsed();
            return RefreshResult.error("EPG 刷新失败");
        }
    }

    private String fetchRemoteHash(String hashUrl) {
        try {
            Request request = new Request.Builder().url(hashUrl).get().build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return "";
                return response.body().string().trim();
            }
        } catch (Exception e) {
            FileLogger.write(TAG, "获取 EPG hash 失败: " + hashUrl, e);
            return "";
        }
    }

    private void downloadXmlToFile(String url, File target) throws Exception {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("HTTP " + response.code());
            }
            try (InputStream in = response.body().byteStream(); OutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[32 * 1024];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
        }

        if (isGzip(target)) {
            File xmlTmp = new File(epgDir, EPG_FILE_NAME + ".unzipped.tmp");
            try (GZIPInputStream in = new GZIPInputStream(new FileInputStream(target));
                 OutputStream out = new FileOutputStream(xmlTmp)) {
                byte[] buffer = new byte[32 * 1024];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
            if (target.exists()) target.delete();
            if (!xmlTmp.renameTo(target)) copyFile(xmlTmp, target);
            if (xmlTmp.exists()) xmlTmp.delete();
        }
    }

    private boolean isGzip(File file) throws Exception {
        try (InputStream in = new FileInputStream(file)) {
            int a = in.read(), b = in.read();
            return a == 0x1f && b == 0x8b;
        }
    }

    private RefreshResult ensureParsed() {
        if (!epgFile.exists() || epgFile.length() == 0) {
            return RefreshResult.error("没有 EPG 缓存文件");
        }
        return RefreshResult.ok();
    }

    private boolean parseXmlForEpgIds(File file, Set<String> requestedEpgIds) {
        if (requestedEpgIds == null || requestedEpgIds.isEmpty()) return true;
        synchronized (parseLock) {
            try {
                final Map<String, ChannelInfo> newByName = new HashMap<>();
                final Map<String, ChannelInfo> newById = new HashMap<>();
                final Map<String, List<EpgProgram>> newPrograms = new HashMap<>();
                final Map<String, List<String>> idsByEpgId = new HashMap<>();
                final Set<String> wantedXmlChannelIds = new HashSet<>();

                XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
                XmlPullParser parser = factory.newPullParser();
                try (InputStream input = new FileInputStream(file)) {
                    parser.setInput(input, null);
                    String channelId = null;
                    ArrayList<String> displayNames = new ArrayList<>();
                    String icon = null;
                    int event = parser.getEventType();
                    while (event != XmlPullParser.END_DOCUMENT) {
                        String tag = parser.getName();
                        if (event == XmlPullParser.START_TAG) {
                            if ("channel".equals(tag)) {
                                channelId = safeText(parser.getAttributeValue(null, "id"));
                                displayNames.clear();
                                icon = null;
                            } else if ("display-name".equals(tag) && channelId != null) {
                                String displayName = safeText(readElementText(parser));
                                if (!displayName.isEmpty()) displayNames.add(displayName);
                            } else if ("icon".equals(tag) && channelId != null && TextUtils.isEmpty(icon)) {
                                icon = safeText(parser.getAttributeValue(null, "src"));
                            }
                        } else if (event == XmlPullParser.END_TAG && "channel".equals(tag)) {
                            if (!TextUtils.isEmpty(channelId)) {
                                String matchedEpgId = null;
                                for (String displayName : displayNames) {
                                    String mappedEpgId = getEpgIdByChannelName(displayName);
                                    if (mappedEpgId != null && requestedEpgIds.contains(mappedEpgId)) {
                                        matchedEpgId = mappedEpgId;
                                        break;
                                    }
                                    if (requestedEpgIds.contains(displayName)) {
                                        matchedEpgId = displayName;
                                        break;
                                    }
                                }
                                if (matchedEpgId != null) {
                                    ChannelInfo info = new ChannelInfo(channelId, matchedEpgId, icon);
                                    newById.put(channelId, info);
                                    List<String> ids = idsByEpgId.get(matchedEpgId);
                                    if (ids == null) {
                                        ids = new ArrayList<>();
                                        idsByEpgId.put(matchedEpgId, ids);
                                    }
                                    if (!ids.contains(channelId)) ids.add(channelId);
                                    ChannelInfo representative = newByName.get(matchedEpgId);
                                    if (representative == null || (TextUtils.isEmpty(representative.iconUrl) && !TextUtils.isEmpty(icon))) {
                                        newByName.put(matchedEpgId, info);
                                    }
                                    wantedXmlChannelIds.add(channelId);
                                    FileLogger.write(TAG, "XMLTV 精确命中: epgid=[" + matchedEpgId
                                            + "] displayName=[" + displayNames + "] -> channel id=[" + channelId + "] icon=[" + icon + "]");
                                }
                            }
                            channelId = null;
                            displayNames.clear();
                            icon = null;
                        }
                        event = parser.next();
                    }
                }

                parser = factory.newPullParser();
                try (InputStream input = new FileInputStream(file)) {
                    parser.setInput(input, null);
                    String programChannel = null;
                    String programStart = null;
                    String programStop = null;
                    String title = "";
                    String desc = "";
                    int event = parser.getEventType();
                    while (event != XmlPullParser.END_DOCUMENT) {
                        String tag = parser.getName();
                        if (event == XmlPullParser.START_TAG) {
                            if ("programme".equals(tag)) {
                                programChannel = safeText(parser.getAttributeValue(null, "channel"));
                                programStart = safeText(parser.getAttributeValue(null, "start"));
                                programStop = safeText(parser.getAttributeValue(null, "stop"));
                                title = "";
                                desc = "";
                            } else if ("title".equals(tag) && programChannel != null) {
                                title = readElementText(parser);
                            } else if ("desc".equals(tag) && programChannel != null) {
                                desc = readElementText(parser);
                            }
                        } else if (event == XmlPullParser.END_TAG && "programme".equals(tag)) {
                            if (!TextUtils.isEmpty(programChannel)
                                    && wantedXmlChannelIds.contains(programChannel)
                                    && !TextUtils.isEmpty(programStart)
                                    && !TextUtils.isEmpty(programStop)) {
                                Date startDate = parseXmltvTime(programStart);
                                Date stopDate = parseXmltvTime(programStop);
                                if (startDate != null && stopDate != null && stopDate.after(startDate)) {
                                    List<EpgProgram> list = newPrograms.get(programChannel);
                                    if (list == null) {
                                        list = new ArrayList<>();
                                        newPrograms.put(programChannel, list);
                                    }
                                    list.add(new EpgProgram(title, desc, startDate, stopDate));
                                }
                            }
                            programChannel = null;
                            programStart = null;
                            programStop = null;
                            title = "";
                            desc = "";
                        }
                        event = parser.next();
                    }
                }

                final Map<String, List<EpgProgram>> newProgramsByEpgId = new HashMap<>();
                for (Map.Entry<String, List<String>> entry : idsByEpgId.entrySet()) {
                    String epgid = entry.getKey();
                    List<EpgProgram> merged = new ArrayList<>();
                    for (String id : entry.getValue()) {
                        List<EpgProgram> list = newPrograms.get(id);
                        if (list != null) merged.addAll(list);
                    }
                    Collections.sort(merged, (a, b) -> {
                        if (a == null || a.start == null) return 1;
                        if (b == null || b.start == null) return -1;
                        return a.start.compareTo(b.start);
                    });
                    newProgramsByEpgId.put(epgid, merged);
                    FileLogger.write(TAG, "EPG完整汇总: epgid=[" + epgid + "] channelIds=" + entry.getValue()
                            + " programmes=" + merged.size() + " dates=" + buildProgramDateKeys(merged));
                }

                channelsByDisplayName.clear();
                channelsByDisplayName.putAll(newByName);
                channelsById.clear();
                channelsById.putAll(newById);
                programsByChannelId.clear();
                programsByChannelId.putAll(newPrograms);
                programsByEpgId.clear();
                programsByEpgId.putAll(newProgramsByEpgId);
                iconUrlByEpgId.clear();
                for (Map.Entry<String, ChannelInfo> e : newByName.entrySet()) {
                    if (e.getValue() != null && !TextUtils.isEmpty(e.getValue().iconUrl)) iconUrlByEpgId.put(e.getKey(), e.getValue().iconUrl);
                }
                xmlChannelIdsByEpgId.clear();
                for (Map.Entry<String, List<String>> entry : idsByEpgId.entrySet()) {
                    xmlChannelIdsByEpgId.put(entry.getKey(), new ArrayList<>(entry.getValue()));
                }
                loadedEpgIds.clear();
                loadedEpgIds.addAll(requestedEpgIds);
                parsed = !newById.isEmpty() || requestedEpgIds.isEmpty();

                int count = 0;
                for (String epgid : newByName.keySet()) {
                    ChannelInfo info = newByName.get(epgid);
                    if (info == null) continue;
                    List<EpgProgram> list = newPrograms.get(info.channelId);
                    if (list != null) count += list.size();
                }
                FileLogger.write(TAG, "按频道组完整懒解析完成: requested=" + requestedEpgIds.size()
                        + ", channels=" + newById.size() + ", epgid=" + newByName.size()
                        + ", programmes=" + count);
                return true;
            } catch (Exception e) {
                FileLogger.write(TAG, "按频道组解析失败", e);
                return false;
            }
        }
    }

    public void loadChannelGroup(final List<String> channelNames, final Runnable onComplete) {
        if (channelNames == null || channelNames.isEmpty()) {
            if (onComplete != null) mainHandler.post(onComplete);
            return;
        }

        final Set<String> requested = new HashSet<>();
        for (String name : channelNames) {
            String epgid = getEpgIdByChannelName(name);
            if (!TextUtils.isEmpty(epgid)) requested.add(epgid);
        }

        if (requested.isEmpty() || !epgFile.exists() || epgFile.length() == 0) {
            if (onComplete != null) mainHandler.post(onComplete);
            return;
        }

        epgExecutor.execute(() -> {
            try {
                synchronized (parseLock) {
                    if (loadedEpgIds.equals(requested) && parsed) {
                        // 同一分组重复进入，不重复扫描 XML。
                    } else {
                        parseXmlForEpgIds(epgFile, requested);
                    }
                }

                for (String channelName : channelNames) {
                    if (TextUtils.isEmpty(channelName)) continue;
                    String epgid = getEpgIdByChannelName(channelName);
                    if (TextUtils.isEmpty(epgid)) continue;
                    ChannelInfo info;
                    synchronized (parseLock) { info = channelsByDisplayName.get(epgid); }
                    if (info != null && !TextUtils.isEmpty(info.iconUrl)) {
                        scheduleIconDownload(epgid, info.iconUrl);
                    }
                }
            } catch (Exception e) {
                FileLogger.write(TAG, "当前频道组 EPG 懒加载失败", e);
            } finally {
                if (onComplete != null) mainHandler.post(onComplete);
            }
        });
    }

    public void preloadGroupResources(List<String> channelNames, Runnable onComplete) {
        loadChannelGroup(channelNames, onComplete);
    }

    public List<EpgProgram> getProgramsForChannel(String channelName) {
        List<EpgProgram> result = new ArrayList<>();
        if (TextUtils.isEmpty(channelName)) return result;
        if (!parsed) return result;
        String epgid = getEpgIdByChannelName(channelName);
        if (TextUtils.isEmpty(epgid)) {
            FileLogger.write(TAG, "EPG严格映射失败① 原始频道名未命中 epg_data.json name: name=[" + channelName + "]");
            return result;
        }
        synchronized (parseLock) {
            List<String> ids = xmlChannelIdsByEpgId.get(epgid);
            if (ids == null || ids.isEmpty()) {
                FileLogger.write(TAG, "EPG映射失败② epgid 未精确命中 XMLTV display-name: name=[" + channelName + "] epgid=[" + epgid + "]");
                return result;
            }
            List<EpgProgram> all = programsByEpgId.get(epgid);
            if (all != null) result.addAll(all);
            String icon = iconUrlByEpgId.get(epgid);
            if (!TextUtils.isEmpty(icon)) scheduleIconDownload(epgid, icon);
            FileLogger.write(TAG, "EPG严格映射完成: name=[" + channelName + "] -> name匹配epgid=[" + epgid
                    + "] -> display-name=[" + epgid + "] -> channel ids=" + ids
                    + " -> 全部programme=" + result.size() + " -> 日期=" + buildProgramDateKeys(result));
        }
        return result;
    }

    public String getEpgIdByChannelNameForLogo(String channelName) {
        return getEpgIdByChannelName(channelName);
    }

    public String getChannelIconUrlForLogo(String channelName) {
        return getChannelIconUrl(channelName);
    }

    public String getChannelIconUrl(String channelName) {
        if (!parsed) return "";
        String epgid = getEpgIdByChannelName(channelName);
        if (TextUtils.isEmpty(epgid)) return "";
        synchronized (parseLock) {
            String icon = iconUrlByEpgId.get(epgid);
            if (TextUtils.isEmpty(icon)) return "";
            scheduleIconDownload(epgid, icon);
            return icon;
        }
    }

    public File getProcessedChannelIconFile(String channelName) {
        return null;
    }

    public void loadProcessedChannelIcon(final String channelName, final IconCallback callback) {
        if (TextUtils.isEmpty(channelName)) {
            if (callback != null) mainHandler.post(() -> callback.onIcon(null));
            return;
        }
        final String epgid = getEpgIdByChannelName(channelName);
        if (TextUtils.isEmpty(epgid) || !parsed) {
            if (callback != null) mainHandler.post(() -> callback.onIcon(null));
            return;
        }
        final File target = new File(logoDir, epgid + ".png");
        final String selectedSource = getLogoSource(context);
        final File sourceMark = new File(logoDir, epgid + ".source");
        if (LOGO_SOURCE_GITHUB.equals(selectedSource)) {
            String mark = readSmallText(sourceMark);
            if (target.exists() && target.length() > 0 && LOGO_SOURCE_GITHUB.equals(mark)) {
                mainHandler.post(() -> { if (callback != null) callback.onIcon(target); });
                return;
            }
            scheduleGithubIconDownload(epgid, target, sourceMark, callback);
            return;
        }
        if (target.exists() && target.length() > 0 && LOGO_SOURCE_EPG.equals(readSmallText(sourceMark))) {
            iconExecutor.execute(() -> {
                try { makeExistingIconTransparent(target); } catch (Exception e) { FileLogger.write(TAG, "本地台标检查失败: " + epgid, e); }
                mainHandler.post(() -> { if (callback != null) callback.onIcon(target.exists() ? target : null); });
            });
            return;
        }
        String iconUrl = getChannelIconUrl(channelName);
        if (TextUtils.isEmpty(iconUrl)) {
            if (callback != null) mainHandler.post(() -> callback.onIcon(null));
            return;
        }
        scheduleIconDownload(epgid, iconUrl, callback);
    }

    public void clearLogoSourceCache() {
        if (!logoDir.exists()) return;
        File[] files = logoDir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f == null) continue;
            String n = f.getName();
            if (n.endsWith(".source") || n.endsWith(".png")) {
                try { f.delete(); } catch (Exception ignored) { }
            }
        }
    }

    public interface IconCallback {
        void onIcon(File file);
    }

    public List<Date> getAvailableDatesForChannel(String channelName) {
        List<Date> result = new ArrayList<>();
        List<EpgProgram> programs = getProgramsForChannel(channelName);
        TimeZone tz = TimeZone.getTimeZone("GMT+8:00");
        for (EpgProgram p : programs) {
            if (p == null || p.start == null || p.stop == null) continue;
            addAllProgramDates(result, p.start, p.stop, tz);
        }
        Collections.sort(result);
        if (!result.isEmpty()) {
            SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            day.setTimeZone(tz);
            StringBuilder dates = new StringBuilder();
            for (Date d : result) { if (dates.length() > 0) dates.append(','); dates.append(day.format(d)); }
            FileLogger.write(TAG, "频道全部EPG日期: name=[" + channelName + "] programmes=" + programs.size() + " dates=" + dates);
        } else {
            FileLogger.write(TAG, "频道无EPG日期: name=[" + channelName + "] programmes=" + programs.size());
        }
        return result;
    }

    private void addAllProgramDates(List<Date> dates, Date start, Date stop, TimeZone tz) {
        Calendar cal = Calendar.getInstance(tz);
        cal.setTime(start);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        Calendar end = Calendar.getInstance(tz);
        end.setTime(new Date(Math.max(start.getTime(), stop.getTime() - 1L)));
        end.set(Calendar.HOUR_OF_DAY, 0);
        end.set(Calendar.MINUTE, 0);
        end.set(Calendar.SECOND, 0);
        end.set(Calendar.MILLISECOND, 0);
        while (!cal.after(end)) {
            addDate(dates, cal.getTime());
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }
    }

    private void addDate(List<Date> dates, Date value) {
        if (value == null) return;
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        day.setTimeZone(TimeZone.getTimeZone("GMT+8:00"));
        String key = day.format(value);
        for (Date d : dates) if (key.equals(day.format(d))) return;
        try {
            Date parsedDay = day.parse(key);
            if (parsedDay != null) dates.add(parsedDay);
        } catch (Exception ignored) { }
    }

    public List<EpgProgram> getAllProgramsForChannel(String channelName) {
        return getProgramsForChannel(channelName);
    }

    public List<EpgProgram> getProgramsForChannelOnDate(String channelName, Date date) {
        List<EpgProgram> result = new ArrayList<>();
        if (date == null) return result;

        TimeZone tz = TimeZone.getTimeZone("GMT+8:00");
        Calendar startCal = Calendar.getInstance(tz);
        startCal.setTime(date);
        startCal.set(Calendar.HOUR_OF_DAY, 0);
        startCal.set(Calendar.MINUTE, 0);
        startCal.set(Calendar.SECOND, 0);
        startCal.set(Calendar.MILLISECOND, 0);
        long dayStart = startCal.getTimeInMillis();
        long dayEnd = dayStart + TimeUnit.DAYS.toMillis(1);

        for (EpgProgram p : getProgramsForChannel(channelName)) {
            if (p == null || p.start == null || p.stop == null) continue;
            if (p.start.getTime() < dayEnd && p.stop.getTime() > dayStart) result.add(p);
        }
        Collections.sort(result, (a, b) -> a.start.compareTo(b.start));
        SimpleDateFormat dayLog = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        dayLog.setTimeZone(tz);
        FileLogger.write(TAG, "按日期读取EPG: name=[" + channelName + "] date=[" + dayLog.format(date) + "] programmes=" + result.size());
        return result;
    }

    public boolean isEpgParsed() {
        return parsed && !channelsById.isEmpty();
    }

    public List<Date> getAllAvailableDates() {
        List<Date> result = new ArrayList<>();
        if (!parsed) return result;
        synchronized (parseLock) {
            for (List<EpgProgram> list : programsByChannelId.values()) {
                if (list == null) continue;
                for (EpgProgram p : list) {
                    if (p == null || p.start == null || p.stop == null) continue;
                    addAllProgramDates(result, p.start, p.stop, TimeZone.getTimeZone("GMT+8:00"));
                }
            }
        }
        Collections.sort(result);
        return result;
    }

    public EpgProgram getCurrentProgram(String channelName) {
        Date now = new Date();
        for (EpgProgram p : getProgramsForChannel(channelName)) {
            if (p.start != null && p.stop != null && !now.before(p.start) && now.before(p.stop)) return p;
        }
        return null;
    }

    public EpgProgram getNextProgram(String channelName) {
        Date now = new Date();
        for (EpgProgram p : getProgramsForChannel(channelName)) {
            if (p.start != null && p.start.after(now)) return p;
        }
        return null;
    }

    // ======================== 台标 ========================
    private void scheduleGithubIconDownload(final String epgid, final File target, final File sourceMark, final IconCallback callback) {
        if (TextUtils.isEmpty(epgid)) { if (callback != null) mainHandler.post(() -> callback.onIcon(null)); return; }
        final String key = "GITHUB:" + epgid;
        if (!iconInFlight.add(key)) {
            if (callback != null) mainHandler.postDelayed(() -> {
                if (target.exists() && target.length() > 0 && LOGO_SOURCE_GITHUB.equals(readSmallText(sourceMark))) callback.onIcon(target);
                else callback.onIcon(null);
            }, 180);
            return;
        }
        iconExecutor.execute(() -> {
            try {
                String url = "https://raw.githubusercontent.com/tytestelle/logo/main/ico/logo/" + epgid + ".png";
                Request request = new Request.Builder().url(url).build();
                Response response = httpClient.newCall(request).execute();
                if (!response.isSuccessful() || response.body() == null) throw new java.io.IOException("HTTP " + response.code());
                byte[] bytes = response.body().bytes();
                Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (b == null) throw new java.io.IOException("PNG解码失败");
                File tmp = new File(target.getParentFile(), target.getName() + ".github_tmp");
                try (FileOutputStream out = new FileOutputStream(tmp, false)) { b.compress(Bitmap.CompressFormat.PNG, 100, out); out.flush(); }
                b.recycle();
                if (!tmp.isFile() || tmp.length() == 0) throw new java.io.IOException("GitHub台标写入失败");
                if (target.exists()) target.delete();
                if (!tmp.renameTo(target)) { copyFile(tmp, target); tmp.delete(); }
                writeSmallText(sourceMark, LOGO_SOURCE_GITHUB);
            } catch (Exception e) {
                FileLogger.write(TAG, "GitHub台标下载失败: " + epgid, e);
            } finally {
                iconInFlight.remove(key);
                final File result = target.exists() && target.length() > 0 && LOGO_SOURCE_GITHUB.equals(readSmallText(sourceMark)) ? target : null;
                if (callback != null) mainHandler.post(() -> callback.onIcon(result));
            }
        });
    }

    private void scheduleIconDownload(final String epgid, final String iconUrl) {
        scheduleIconDownload(epgid, iconUrl, null);
    }

    private void scheduleIconDownload(final String epgid, final String iconUrl, final IconCallback callback) {
        if (TextUtils.isEmpty(epgid) || TextUtils.isEmpty(iconUrl)) {
            if (callback != null) mainHandler.post(() -> callback.onIcon(null));
            return;
        }
        final File target = new File(logoDir, epgid + ".png");
        if (!iconInFlight.add(epgid)) {
            if (callback != null) {
                final Runnable[] checker = new Runnable[1];
                checker[0] = () -> {
                    if (target.exists() && target.length() > 0) {
                        callback.onIcon(target);
                    } else if (iconInFlight.contains(epgid)) {
                        mainHandler.postDelayed(checker[0], 120);
                    } else {
                        callback.onIcon(null);
                    }
                };
                mainHandler.postDelayed(checker[0], 120);
            }
            return;
        }
        iconExecutor.execute(() -> {
            try {
                if (target.exists() && target.length() > 0) {
                    makeExistingIconTransparent(target);
                } else {
                    downloadAndProcessIcon(epgid, iconUrl, target);
                }
            } catch (Exception e) {
                FileLogger.write(TAG, "台标任务异常: " + epgid, e);
            } finally {
                iconInFlight.remove(epgid);
                if (callback != null) {
                    final File result = target.exists() && target.length() > 0 ? target : null;
                    mainHandler.post(() -> callback.onIcon(result));
                }
            }
        });
    }

    private void makeExistingIconTransparent(File target) {
        if (target == null || !target.exists() || target.length() <= 0) return;
        Bitmap bitmap = null;
        Bitmap transparent = null;
        File tmp = null;
        try {
            if (!logoDir.exists() && !logoDir.mkdirs() && !logoDir.isDirectory()) {
                throw new java.io.IOException("无法创建台标目录: " + logoDir.getAbsolutePath());
            }
            bitmap = BitmapFactory.decodeFile(target.getAbsolutePath());
            if (bitmap == null) throw new java.io.IOException("Bitmap 解码失败");
            transparent = makeTransparent(bitmap);
            if (transparent == null) throw new java.io.IOException("透明化结果为空");

            tmp = new File(target.getParentFile(), target.getName() + ".tmp_" + System.nanoTime());
            if (tmp.exists()) tmp.delete();
            try (FileOutputStream out = new FileOutputStream(tmp, false)) {
                if (!transparent.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw new java.io.IOException("PNG 压缩写入失败");
                }
                out.flush();
                try { out.getFD().sync(); } catch (Exception ignored) { }
            }
            if (!tmp.isFile() || tmp.length() <= 0) {
                throw new java.io.IOException("临时 PNG 写入后不存在或为空: " + tmp.getAbsolutePath());
            }
            Bitmap verify = BitmapFactory.decodeFile(tmp.getAbsolutePath());
            if (verify == null) throw new java.io.IOException("临时 PNG 解码校验失败");
            verify.recycle();

            File backup = new File(target.getParentFile(), target.getName() + ".bak");
            if (backup.exists()) backup.delete();
            if (!target.renameTo(backup)) {
                if (!target.delete() && target.exists()) throw new java.io.IOException("无法替换旧台标");
            }
            boolean installed = tmp.renameTo(target);
            if (!installed) {
                copyFile(tmp, target);
                installed = target.isFile() && target.length() > 0;
            }
            if (!installed) {
                if (backup.isFile() && !target.exists()) backup.renameTo(target);
                throw new java.io.IOException("透明 PNG 安装失败");
            }
            if (backup.exists()) backup.delete();
            if (tmp.exists()) tmp.delete();
            writeSmallText(new File(target.getParentFile(), target.getName().replace(".png", ".source")), LOGO_SOURCE_EPG);
        } catch (Exception e) {
            FileLogger.write(TAG, "本地台标透明化失败: " + (target == null ? "null" : target.getName()), e);
            if (tmp != null && tmp.exists()) tmp.delete();
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            if (transparent != null && !transparent.isRecycled()) transparent.recycle();
        }
    }

    private void downloadAndProcessIcon(String epgid, String iconUrl, File target) {
        Bitmap bitmap = null;
        Bitmap transparent = null;
        File tmp = null;
        try {
            Request request = new Request.Builder().url(iconUrl).get().build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return;
                byte[] data = response.body().bytes();
                bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (bitmap == null) return;
                transparent = makeTransparent(bitmap);
                if (transparent == null) return;

                if (!logoDir.exists() && !logoDir.mkdirs() && !logoDir.isDirectory()) return;
                tmp = File.createTempFile("logo_", ".png", logoDir);
                try (FileOutputStream out = new FileOutputStream(tmp, false)) {
                    if (!transparent.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        throw new java.io.IOException("PNG 写入失败");
                    }
                    out.flush();
                    try { out.getFD().sync(); } catch (Exception ignored) { }
                }
                if (!tmp.exists() || tmp.length() <= 0) throw new java.io.IOException("临时 PNG 未生成");

                File backup = new File(logoDir, target.getName() + ".bak");
                if (backup.exists()) backup.delete();
                boolean movedOld = target.exists() && target.renameTo(backup);
                boolean installed = tmp.renameTo(target);
                if (!installed) {
                    copyFile(tmp, target);
                    installed = target.exists() && target.length() > 0;
                }
                if (!installed) {
                    if (movedOld && !target.exists()) backup.renameTo(target);
                    throw new java.io.IOException("透明 PNG 替换失败");
                }
                if (backup.exists()) backup.delete();
                if (tmp.exists()) tmp.delete();
                writeSmallText(new File(logoDir, epgid + ".source"), LOGO_SOURCE_EPG);
                FileLogger.write(TAG, "EPG 台标已保存(透明 PNG): " + target.getAbsolutePath());
            }
        } catch (Exception e) {
            FileLogger.write(TAG, "EPG 台标处理失败: " + epgid, e);
            if (tmp != null && tmp.exists()) tmp.delete();
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            if (transparent != null && !transparent.isRecycled()) transparent.recycle();
        }
    }

    private String readSmallText(File file) {
        if (file == null || !file.isFile()) return "";
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[64]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) { return ""; }
    }

    private void writeSmallText(File file, String value) {
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception e) { FileLogger.write(TAG, "台标来源标记写入失败: " + file, e); }
    }

    /**
     * V7：关键修复 —— 从零创建 ARGB_8888 Bitmap 并显式 setHasAlpha(true)。
     * 之前 src.copy(...) 会继承 JPG 解码后 hasAlpha=false 标志，导致
     * PNG 编码器不写 alpha 通道，透明像素被当作白色。
     */
    private Bitmap makeTransparent(Bitmap src) {
        if (src == null) return null;
        final int width = src.getWidth(), height = src.getHeight();
        // 关键：从零创建 ARGB_8888 bitmap，并显式声明 hasAlpha=true，
        // 否则 JPG 解码出来的 bitmap（hasAlpha=false）会让 PNG 编码器丢弃 alpha 通道。
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        result.setHasAlpha(true);

        int[] pixels = new int[width * height];
        src.getPixels(pixels, 0, width, 0, 0, width, height);

        int transparent = 0;
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
            int max = Math.max(r, Math.max(g, b));
            int min = Math.min(r, Math.min(g, b));
            int spread = max - min;

            if (max >= 235 && spread <= 20) {
                pixels[i] = Color.argb(0, r, g, b); transparent++; continue;
            }
            if (max >= 215 && spread <= 35) {
                pixels[i] = Color.argb(0, r, g, b); transparent++; continue;
            }
            if (max >= 195 && spread <= 50) {
                pixels[i] = Color.argb(0, r, g, b); transparent++; continue;
            }
            if (max >= 175 && spread <= 60) {
                int alpha = (int) (100 + (max - 175) * 120 / 20.0);
                alpha = Math.max(60, Math.min(230, alpha));
                pixels[i] = Color.argb(alpha, r, g, b); transparent++; continue;
            }
        }

        result.setPixels(pixels, 0, width, 0, 0, width, height);
        result.setHasAlpha(true);  // 双保险

        // 打印四角 alpha 便于验证
        int tl = Color.alpha(pixels[0]);
        int tr = Color.alpha(pixels[width - 1]);
        int bl = Color.alpha(pixels[(height - 1) * width]);
        int br = Color.alpha(pixels[height * width - 1]);
        FileLogger.write(TAG, "台标透明化完成V7: " + width + "x" + height
                + " transparent=" + transparent + " 四角alpha=[" + tl + "," + tr + "," + bl + "," + br + "]");
        return result;
    }

    private int mostCommonColorExact(ArrayList<Integer> samples) {
        if (samples == null || samples.isEmpty()) return Color.WHITE;
        HashMap<Integer, Integer> counts = new HashMap<>();
        int best = Color.WHITE;
        int bestCount = 0;
        for (Integer c : samples) {
            if (c == null) continue;
            int n = counts.containsKey(c) ? counts.get(c) + 1 : 1;
            counts.put(c, n);
            if (n > bestCount) {
                bestCount = n;
                best = c;
            }
        }
        return best;
    }

    private int mostCommonColorQuantized(ArrayList<Integer> samples) {
        if (samples == null || samples.isEmpty()) return Color.WHITE;
        HashMap<Integer, Integer> count = new HashMap<>();
        int bestKey = 0, bestCount = 0;
        for (Integer c : samples) {
            if (c == null) continue;
            int r = Color.red(c) >> 3, g = Color.green(c) >> 3, b = Color.blue(c) >> 3;
            int key = (r << 10) | (g << 5) | b;
            int n = count.containsKey(key) ? count.get(key) + 1 : 1;
            count.put(key, n);
            if (n > bestCount) { bestCount = n; bestKey = key; }
        }
        int r = ((bestKey >> 10) & 31) * 8 + 4;
        int g = ((bestKey >> 5) & 31) * 8 + 4;
        int b = (bestKey & 31) * 8 + 4;
        return Color.rgb(Math.min(255,r), Math.min(255,g), Math.min(255,b));
    }

    private int enqueueBackground(int x, int y, int width, int height, int[] pixels,
                                   boolean[] visited, int[] queue, int tail,
                                   int br, int bgc, int bb, int tolerance2) {
        if (x < 0 || x >= width || y < 0 || y >= height) return tail;
        int pos = y * width + x;
        if (visited[pos]) return tail;
        if (!isBackgroundLike(pixels[pos], br, bgc, bb, tolerance2)) return tail;
        visited[pos] = true;
        queue[tail++] = pos;
        return tail;
    }

    private boolean isBackgroundLike(int color, int br, int bg, int bb, int tolerance2) {
        if (Color.alpha(color) == 0) return true;
        int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        int dr = r - br, dg = g - bg, db = b - bb;
        if (dr * dr + dg * dg + db * db <= tolerance2) return true;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        int neutral = max - min;
        if (br >= 200 && bg >= 200 && bb >= 200) return min >= 175 && neutral <= 28;
        if (br <= 80 && bg <= 80 && bb <= 80) return max <= 105 && neutral <= 28;
        return neutral <= 18 && (max >= 220 || max <= 75);
    }

    private void addBackgroundSample(ArrayList<Integer> samples, int[] pixels, int width, int height, int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) return;
        int c = pixels[y * width + x];
        if (Color.alpha(c) > 0) samples.add(Color.rgb(Color.red(c), Color.green(c), Color.blue(c)));
    }

    private int mostCommonColor(ArrayList<Integer> samples) {
        if (samples == null || samples.isEmpty()) return Color.WHITE;
        HashMap<Integer, Integer> counts = new HashMap<>();
        int best = samples.get(0), bestCount = 0;
        for (Integer c : samples) {
            if (c == null) continue;
            int n = counts.containsKey(c) ? counts.get(c) + 1 : 1;
            counts.put(c, n);
            if (n > bestCount) { bestCount = n; best = c; }
        }
        return best;
    }

    // ======================== 工具 ========================
    private String buildProgramDateKeys(List<EpgProgram> programs) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (programs == null) return "[]";
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        day.setTimeZone(TimeZone.getTimeZone("GMT+8:00"));
        for (EpgProgram p : programs) {
            if (p == null || p.start == null || p.stop == null) continue;
            Calendar c = Calendar.getInstance(TimeZone.getTimeZone("GMT+8:00"));
            c.setTime(p.start);
            Calendar end = Calendar.getInstance(TimeZone.getTimeZone("GMT+8:00"));
            end.setTime(p.stop);
            while (!c.after(end)) {
                keys.add(day.format(c.getTime()));
                c.add(Calendar.DAY_OF_MONTH, 1);
                if (keys.size() > 31) break;
            }
        }
        return keys.toString();
    }

    private static String readElementText(XmlPullParser parser) throws Exception {
        StringBuilder sb = new StringBuilder();
        final int startDepth = parser.getDepth();
        int event = parser.next();
        while (!(event == XmlPullParser.END_TAG && parser.getDepth() == startDepth)) {
            if (event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT || event == XmlPullParser.ENTITY_REF) {
                String text = parser.getText();
                if (text != null) sb.append(text);
            }
            event = parser.next();
        }
        return sb.toString();
    }

    private static String safeText(String value) { return value == null ? "" : value.trim(); }

    private Date parseXmltvTime(String value) {
        if (TextUtils.isEmpty(value) || value.length() < 14) return null;
        try {
            String main = value.substring(0, 14);
            String zone = value.length() >= 19 ? value.substring(15).trim() : "+0800";
            if (zone.isEmpty()) zone = "+0800";
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US);
            sdf.setLenient(false);
            return sdf.parse(main + " " + zone);
        } catch (Exception first) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
                sdf.setTimeZone(TimeZone.getTimeZone("GMT+8:00"));
                return sdf.parse(value.substring(0, 14));
            } catch (Exception second) {
                FileLogger.write(TAG, "时间解析失败: " + value);
                return null;
            }
        }
    }

    private static String readText(File file) {
        if (!file.exists()) return "";
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toString("UTF-8").trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static void writeTextAtomically(File target, String text) throws Exception {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        if (target.exists()) target.delete();
        if (!tmp.renameTo(target)) copyFile(tmp, target);
        if (tmp.exists()) tmp.delete();
    }

    private static void copyFile(File source, File target) throws Exception {
        try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[32 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        }
    }

    private static class ChannelInfo {
        final String channelId;
        final String displayName;
        final String iconUrl;
        ChannelInfo(String channelId, String displayName, String iconUrl) {
            this.channelId = channelId;
            this.displayName = displayName;
            this.iconUrl = iconUrl;
        }
    }

    private static class CalendarDay {
        final Date date;
        final long millis;
        private CalendarDay(Date date, long millis) { this.date = date; this.millis = millis; }
        static CalendarDay startOfDay(Date date) {
            java.util.Calendar c = java.util.Calendar.getInstance(TimeZone.getTimeZone("GMT+8:00"));
            c.setTime(date);
            c.set(java.util.Calendar.HOUR_OF_DAY, 0);
            c.set(java.util.Calendar.MINUTE, 0);
            c.set(java.util.Calendar.SECOND, 0);
            c.set(java.util.Calendar.MILLISECOND, 0);
            Date d = c.getTime();
            return new CalendarDay(d, d.getTime());
        }
    }

    private static class RefreshResult {
        final boolean success;
        final String message;
        private RefreshResult(boolean success, String message) { this.success = success; this.message = message; }
        static RefreshResult ok() { return new RefreshResult(true, ""); }
        static RefreshResult error(String msg) { return new RefreshResult(false, msg); }
    }

    public interface RefreshCallback {
        void onSuccess();
        void onError(String msg);
    }

    public static class EpgProgram {
        public String title, description;
        public Date start, stop;
        public EpgProgram(String title, String description, Date start, Date stop) {
            this.title = title == null ? "" : title;
            this.description = description == null ? "" : description;
            this.start = start;
            this.stop = stop;
        }
    }
}
