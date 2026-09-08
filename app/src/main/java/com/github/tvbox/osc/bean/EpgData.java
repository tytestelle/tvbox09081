package com.github.tvbox.osc.bean;

import androidx.annotation.NonNull;
import androidx.room.Entity;

@Entity(tableName = "epg_data", primaryKeys = {"channelName", "date", "idx"})
public class EpgData {
    @NonNull
    public String channelName = "";
    @NonNull
    public String date = "";
    public String title = "";
    public String start = "";
    public String end = "";
    public long startTime = 0;
    public long endTime = 0;
    public int idx = 0;

    public EpgData() {}

    @androidx.room.Ignore
    public EpgData(String channelName, String date, String title, String start, String end, long startTime, long endTime, int idx) {
        this.channelName = channelName;
        this.date = date;
        this.title = title;
        this.start = start;
        this.end = end;
        this.startTime = startTime;
        this.endTime = endTime;
        this.idx = idx;
    }
}
