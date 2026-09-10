package com.github.tvbox.osc.cache;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.github.tvbox.osc.bean.EpgChannel;

import java.util.List;

@Dao
public interface EpgChannelDao {
    @Query("SELECT * FROM epg_channel WHERE name = :name LIMIT 1")
    EpgChannel getByName(String name);

    @Query("SELECT COUNT(*) FROM epg_channel")
    int getCount();

    @Query("SELECT * FROM epg_channel")
    List<EpgChannel> getAll();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<EpgChannel> channels);

    @Query("DELETE FROM epg_channel")
    void deleteAll();
}
