package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.ui.adapter.ApiHistoryDialogAdapter;
import com.owen.tvrecyclerview.widget.TvRecyclerView;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public class ApiHistoryDialog extends BaseDialog {
    public ApiHistoryDialog(@NonNull @NotNull Context context) {
        super(context, R.style.CustomDialogStyleDim);
        setContentView(R.layout.dialog_api_history);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    public void setTip(String tip) {
        ((TextView) findViewById(R.id.title)).setText(tip);
    }

    public void setAdapter(ApiHistoryDialogAdapter.SelectDialogInterface sourceBeanSelectDialogInterface, List<String> data, int select) {
        ApiHistoryDialogAdapter adapter = new ApiHistoryDialogAdapter(sourceBeanSelectDialogInterface);
        if (data == null || data.isEmpty()) data = java.util.Collections.singletonList("");
        if (select < 0 || select >= data.size()) select = 0;
        adapter.setData(data, select);
        TvRecyclerView tvRecyclerView = ((TvRecyclerView) findViewById(R.id.list));
        tvRecyclerView.setAdapter(adapter);
        tvRecyclerView.setSelectedPosition(select);
        final int selected = select;
        tvRecyclerView.post(new Runnable() {
            @Override
            public void run() {
                tvRecyclerView.scrollToPosition(selected);
            }
        });
    }

    public void setAddListener(View.OnClickListener listener) {
        View v = findViewById(R.id.btnAddSubscription);
        if (v != null) v.setOnClickListener(listener);
    }
}
