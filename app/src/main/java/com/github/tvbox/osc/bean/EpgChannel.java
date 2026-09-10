package com.github.tvbox.osc.bean;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "epg_channel", indices = {@Index(value = {"epgid"})})
public class EpgChannel {
    @PrimaryKey @NonNull
    public String name = "";
    public String logo = "";
    public String epgid = "";
    public String aliases = "";
    public long updateTime = 0;

    public EpgChannel() {}

    @androidx.room.Ignore
    public EpgChannel(String name, String logo, String epgid, String aliases) {
        this.name = name;
        this.logo = logo;
        this.epgid = epgid;
        this.aliases = aliases;
        this.updateTime = System.currentTimeMillis();
    }
}
