package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;

import org.jetbrains.annotations.NotNull;

public class InputDialog extends BaseDialog {

    private TextView tvTitle;
    private EditText etInput;
    private TextView tvConfirm;
    private TextView tvCancel;
    private OnConfirmListener listener;

    public InputDialog(@NonNull @NotNull Context context) {
        super(context);
        setContentView(R.layout.dialog_input);
        tvTitle = findViewById(R.id.inputTitle);
        etInput = findViewById(R.id.etInput);
        tvConfirm = findViewById(R.id.tvConfirm);
        tvCancel = findViewById(R.id.tvCancel);

        tvConfirm.setOnClickListener(v -> {
            if (listener != null) {
                listener.onConfirm(etInput.getText().toString());
            }
            dismiss();
        });

        tvCancel.setOnClickListener(v -> dismiss());
    }

    public InputDialog setTitle(String title) {
        if (tvTitle != null) tvTitle.setText(title);
        return this;
    }

    public InputDialog setHint(String hint) {
        if (etInput != null) etInput.setHint(hint);
        return this;
    }

    public InputDialog setDefaultText(String text) {
        if (etInput != null) {
            etInput.setText(text);
            etInput.setSelection(text.length());
        }
        return this;
    }

    public InputDialog setOnConfirmListener(OnConfirmListener listener) {
        this.listener = listener;
        return this;
    }

    public interface OnConfirmListener {
        void onConfirm(String text);
    }
}
