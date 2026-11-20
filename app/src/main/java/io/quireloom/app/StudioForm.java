package io.quireloom.app;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.function.Consumer;

final class StudioForm {
    private final Context context;
    final LinearLayout fields;

    StudioForm(Context context) {
        this.context = context;
        fields = new LinearLayout(context);
        fields.setOrientation(LinearLayout.VERTICAL);
        int horizontal = dp(24);
        fields.setPadding(horizontal, dp(12), horizontal, dp(8));
    }

    int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    TextView label(String value) {
        var label = new TextView(context);
        label.setText(value);
        label.setTextIsSelectable(true);
        label.setPadding(0, dp(8), 0, dp(5));
        fields.addView(label);
        return label;
    }

    EditText text(String label, String value, boolean multiline) {
        TextView caption = label(label);
        var field = new EditText(context);
        field.setId(View.generateViewId());
        caption.setLabelFor(field.getId());
        field.setInputType(multiline ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
            : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setText(value);
        if (multiline) field.setMinLines(3);
        fields.addView(field);
        return field;
    }

    EditText number(String label, int value) {
        var field = text(label, String.valueOf(value), false);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        return field;
    }

    CheckBox check(String label, boolean checked) {
        var box = new CheckBox(context);
        box.setText(label);
        box.setChecked(checked);
        box.setMinHeight(dp(48));
        fields.addView(box);
        return box;
    }

    Spinner select(String label, String[] choices, int selected) {
        var caption = label(label);
        var spinner = new Spinner(context);
        spinner.setId(View.generateViewId());
        caption.setLabelFor(spinner.getId());
        var adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, choices);
        spinner.setAdapter(adapter);
        spinner.setSelection(selected);
        spinner.setMinimumHeight(dp(48));
        fields.addView(spinner);
        return spinner;
    }

    ScrollView scroll() {
        var scroll = new ScrollView(context);
        scroll.addView(fields);
        scroll.setFillViewport(false);
        return scroll;
    }

    AlertDialog confirm(String title, String action, Consumer<AlertDialog> apply, Runnable cancel) {
        var dialog = new AlertDialog.Builder(context).setTitle(title).setView(scroll())
            .setPositiveButton(action, null).setNegativeButton("Cancel", (d, w) -> cancel.run()).create();
        dialog.setOnCancelListener(d -> cancel.run());
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> apply.accept(dialog));
        return dialog;
    }
}
