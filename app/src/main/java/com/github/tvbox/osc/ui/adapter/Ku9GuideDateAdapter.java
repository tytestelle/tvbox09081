package com.github.tvbox.osc.ui.adapter;

import android.graphics.Color;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.LiveEpgDate;

import java.util.ArrayList;

public class Ku9GuideDateAdapter extends BaseQuickAdapter<LiveEpgDate, BaseViewHolder> {
    private int selectedIndex = -1;
    private int focusedIndex = -1;

    public Ku9GuideDateAdapter() { super(R.layout.item_ku9_guide_date, new ArrayList<>()); }

    @Override protected void convert(BaseViewHolder holder, LiveEpgDate item) {
        TextView tv = holder.getView(R.id.tv_guide_date);
        tv.setText(item.getDatePresented());
        holder.itemView.setSelected(item.getIndex() == selectedIndex);
        int color = (item.getIndex() == selectedIndex && item.getIndex() != focusedIndex)
                ? Color.rgb(45, 112, 235) : Color.WHITE;
        tv.setTextColor(color);
    }

    public void setSelectedIndex(int index) {
        int old = selectedIndex; selectedIndex = index;
        if (old >= 0) notifyItemChanged(old);
        if (index >= 0) notifyItemChanged(index);
    }
    public int getSelectedIndex() { return selectedIndex; }
    public void setFocusedIndex(int index) {
        int old = focusedIndex; focusedIndex = index;
        if (old >= 0) notifyItemChanged(old);
        if (index >= 0) notifyItemChanged(index);
    }
}
