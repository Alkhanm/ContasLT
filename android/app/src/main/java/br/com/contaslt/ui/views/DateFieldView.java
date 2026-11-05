package br.com.contaslt.ui.views;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.function.Consumer;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import com.google.android.material.datepicker.MaterialDatePicker;

import br.com.contaslt.R;
import br.com.contaslt.ui.Format;
import br.com.contaslt.ui.Ui;

/** Campo de data (DateInput.vue): "dd/mm/aaaa" com ícone de calendário; abre o seletor do Material. */
public class DateFieldView extends LinearLayout {

    private final TextView text;
    private String value;
    private Consumer<String> listener;
    private boolean clearable;

    public DateFieldView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBackgroundResource(R.drawable.bg_input);
        setMinimumHeight(Ui.dp(context, 44));
        int p = Ui.dp(context, 12);
        setPadding(p, 0, p, 0);
        text = new TextView(context, null, 0, R.style.Text);
        addView(text, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        ImageView icon = new ImageView(context);
        icon.setImageResource(R.drawable.ic_calendar_days);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.color(context, R.color.muted_foreground)));
        addView(icon, new LayoutParams(Ui.dp(context, 18), Ui.dp(context, 18)));
        setOnClickListener(v -> open());
        render();
    }

    public String value() {
        return value;
    }

    public void setValue(String iso) {
        value = iso;
        render();
    }

    public void setOnChange(Consumer<String> l) {
        listener = l;
    }

    /** Permite apagar (ex.: data final opcional). */
    public void setClearable(boolean c) {
        clearable = c;
    }

    private void render() {
        text.setText(value == null ? "dd/mm/aaaa" : Format.dateNumeric(value));
        text.setTextColor(Ui.color(getContext(), value == null ? R.color.muted_foreground : R.color.foreground));
    }

    /** A Activity por trás do contexto (dentro de diálogos o contexto é um ContextWrapper). */
    private static FragmentActivity activity(Context c) {
        while (c instanceof android.content.ContextWrapper) {
            if (c instanceof FragmentActivity a) {
                return a;
            }
            c = ((android.content.ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    private void open() {
        FragmentActivity act = activity(getContext());
        if (act == null) {
            return;
        }
        LocalDate d = value == null ? LocalDate.now() : LocalDate.parse(value);
        MaterialDatePicker.Builder<Long> builder = MaterialDatePicker.Builder.datePicker()
            .setSelection(d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli());
        if (clearable) {
            builder.setNegativeButtonText("Limpar");
        }
        MaterialDatePicker<Long> picker = builder.build();
        picker.addOnPositiveButtonClickListener(ms -> {
            value = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString();
            render();
            if (listener != null) {
                listener.accept(value);
            }
        });
        if (clearable) {
            picker.addOnNegativeButtonClickListener(v -> {
                value = null;
                render();
                if (listener != null) {
                    listener.accept(null);
                }
            });
        }
        picker.show(act.getSupportFragmentManager(), "date");
    }
}
