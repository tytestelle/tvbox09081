package com.github.tvbox.osc.util.epg;

import android.content.Context;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import com.github.tvbox.osc.util.FileLogger;
/**
 * EPG 数据加载器，从 assets/epg_data.json 读取频道名称到 epgid 的映射。
 * 支持逗号分隔的多个名称对应同一个 epgid，自动去除空格。
 */
public class EpgDataLoader {
    private static Map<String, String> nameToEpgId = new HashMap<>();
    private static boolean loaded = false;

    /**
     * 加载 EPG 映射数据，线程安全，仅执行一次。
     * @param context 上下文，用于访问 assets
     */
    public static synchronized void load(Context context) {
        if (loaded) return;
        try {
            InputStream is = context.getAssets().open("epg_data.json");
            JsonObject root = JsonParser.parseReader(new InputStreamReader(is)).getAsJsonObject();
            JsonArray epgs = root.getAsJsonArray("epgs");
            for (int i = 0; i < epgs.size(); i++) {
                JsonObject item = epgs.get(i).getAsJsonObject();
                String epgid = item.get("epgid").getAsString();
                String names = item.get("name").getAsString();
                for (String name : names.split(",")) {
                    name = name.trim();
                    if (!name.isEmpty()) {
                        nameToEpgId.put(name, epgid);
                        FileLogger.write("EpgDataLoader", "映射: " + name + " -> " + epgid);
                    }
                }
            }
            loaded = true;
            FileLogger.write("EpgDataLoader", "加载完成，共 " + nameToEpgId.size() + " 条映射");
        } catch (Exception e) {
            FileLogger.write("EpgDataLoader", "加载失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 根据频道名称获取对应的 epgid。
     * @param channelName 频道名称
     * @return epgid，若不存在则返回 null
     */
    public static String getEpgId(String channelName) {
        return nameToEpgId.get(channelName);
    }

    /**
     * 获取所有名称到 epgid 的映射副本。
     * @return 不可修改的映射副本
     */
    public static Map<String, String> getAllMappings() {
        return new HashMap<>(nameToEpgId);
    }
}
