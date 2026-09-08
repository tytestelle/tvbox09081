package com.github.tvbox.osc.cache;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.github.tvbox.osc.bean.EpgData;

import java.util.List;

@Dao
public interface EpgDataDao {
    @Query("SELECT * FROM epg_data WHERE channelName = :name AND date = :date ORDER BY idx ASC")
    List<EpgData> get(String name, String date);

    @Query("SELECT COUNT(*) FROM epg_data WHERE channelName = :name AND date = :date")
    int count(String name, String date);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<EpgData> list);

    @Query("DELETE FROM epg_data WHERE channelName = :name AND date = :date")
    void delete(String name, String date);

    @Query("DELETE FROM epg_data WHERE date < :expire")
    void deleteExpired(String expire);
}
