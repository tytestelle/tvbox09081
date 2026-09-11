package com.github.tvbox.osc.ui.adapter;

import android.graphics.Color;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.LiveChannelGroup;

import java.util.ArrayList;

public class LiveChannelGroupAdapter extends BaseQuickAdapter<LiveChannelGroup, BaseViewHolder> {
    private int selectedGroupIndex = -1;
    private int focusedGroupIndex = -1;

    public LiveChannelGroupAdapter() {
        super(R.layout.item_live_channel_group, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder holder, LiveChannelGroup item) {
        TextView tvGroupName = holder.getView(R.id.tvChannelGroupName);
        tvGroupName.setText(item.getGroupName());
        int groupIndex = item.getGroupIndex();

        // 选中状态优先级最高
        if (groupIndex == selectedGroupIndex) {
            // ★ 修改点1：将原来的蓝色文字改为高亮金黄色，彻底解决蓝底蓝字看不见的问题
            tvGroupName.setTextColor(Color.parseColor("#FFFFD54F")); 
            tvGroupName.setBackgroundResource(R.drawable.bg_live_group_selected);
        } else if (groupIndex == focusedGroupIndex) {
            // ★ 修改点2：确保焦点状态是白字，防止焦点背景也是浅色
            tvGroupName.setTextColor(Color.WHITE);
            tvGroupName.setBackgroundResource(R.drawable.bg_live_group_focused);
        } else {
            // 未选中状态：白字，透明背景
            tvGroupName.setTextColor(Color.WHITE);
            tvGroupName.setBackgroundResource(android.R.color.transparent);
        }
    }

    public void setSelectedGroupIndex(int selectedGroupIndex) {
        if (selectedGroupIndex == this.selectedGroupIndex) return;
        int preSelectedGroupIndex = this.selectedGroupIndex;
        this.selectedGroupIndex = selectedGroupIndex;
        notifyGroupChanged(preSelectedGroupIndex);
        notifyGroupChanged(this.selectedGroupIndex);
    }

    public int getSelectedGroupIndex() {
        return selectedGroupIndex;
    }

    public void setFocusedGroupIndex(int focusedGroupIndex) {
        this.focusedGroupIndex = focusedGroupIndex;
        if (this.focusedGroupIndex != -1)
            notifyGroupChanged(this.focusedGroupIndex);
        else if (this.selectedGroupIndex != -1)
            notifyGroupChanged(this.selectedGroupIndex);
    }

    public void clearGroupState() {
        selectedGroupIndex = -1;
        focusedGroupIndex = -1;
    }

    private void notifyGroupChanged(int position) {
        if (position >= 0 && position < getItemCount()) {
            notifyItemChanged(position);
        }
    }
}
