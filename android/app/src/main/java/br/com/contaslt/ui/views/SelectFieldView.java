package br.com.contaslt.ui.views;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import br.com.contaslt.R;
import br.com.contaslt.ui.Ui;

/**
 * Select do PWA: campo com o valor escolhido (ícone/bolinha de cor opcional) e chevron; ao tocar, abre a lista de
 * opções (com grupos) numa bottom sheet.
 */
public class SelectFieldView extends LinearLayout {

    /** Opção: chave (null = "nenhum"), texto, ícone Lucide e cor (ícone de categoria) ou só cor (bolinha). */
    public static final class Option {
        public final String key;
        public final String label;
        public final String icon;
        public final String color;
        /** Cabeçalho de grupo (não selecionável). */
        public final boolean header;
        public final boolean muted;

        private Option(String key, String label, String icon, String color, boolean header, boolean muted) {
            this.key = key;
            this.label = label;
            this.icon = icon;
            this.color = color;
            this.header = header;
            this.muted = muted;
        }

        public static Option of(String key, String label) {
            return new Option(key, label, null, null, false, false);
        }

        public static Option muted(String key, String label) {
            return new Option(key, label, null, null, false, true);
        }

        public static Option icon(String key, String label, String icon, String color) {
            return new Option(key, label, icon, color, false, false);
        }

        public static Option dot(String key, String label, String color) {
            return new Option(key, label, null, color, false, false);
        }

        public static Option header(String label) {
            return new Option(null, label, null, null, true, false);
        }
    }

    private final LinearLayout valueBox;
    private List<Option> options = new ArrayList<>();
    private String value;
    private String placeholder = "Selecione";
    private String sheetTitle;
    private Consumer<String> listener;

    public SelectFieldView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBackgroundResource(R.drawable.bg_input);
        setMinimumHeight(Ui.dp(context, 44));
        int p = Ui.dp(context, 12);
        setPadding(p, 0, p, 0);
        valueBox = new LinearLayout(context);
        valueBox.setGravity(Gravity.CENTER_VERTICAL);
        addView(valueBox, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        ImageView chevron = new ImageView(context);
        chevron.setImageResource(R.drawable.ic_chevron_down);
        chevron.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.color(context, R.color.muted_foreground)));
        addView(chevron, new LayoutParams(Ui.dp(context, 16), Ui.dp(context, 16)));
        setOnClickListener(v -> open());
        render();
    }

    public void setOptions(List<Option> options) {
        this.options = options;
        render();
    }

    public void setPlaceholder(String p) {
        placeholder = p;
        render();
    }

    public void setSheetTitle(String t) {
        sheetTitle = t;
    }

    public void setOnChange(Consumer<String> l) {
        listener = l;
    }

    public String value() {
        return value;
    }

    public void setValue(String key) {
        value = key;
        render();
    }

    private Option current() {
        for (Option o : options) {
            if (!o.header && (o.key == null ? value == null : o.key.equals(value))) {
                return o;
            }
        }
        return null;
    }

    private void render() {
        valueBox.removeAllViews();
        Option o = current();
        if (o == null) {
            TextView tv = label(placeholder, true);
            valueBox.addView(tv, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
            return;
        }
        fill(valueBox, o);
    }

    private void fill(LinearLayout box, Option o) {
        Context c = getContext();
        if (o.icon != null) {
            CategoryIconView icon = new CategoryIconView(c);
            icon.setSize("sm");
            icon.bind(o.icon, o.color);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(c, 28), Ui.dp(c, 28));
            lp.setMarginEnd(Ui.dp(c, 8));
            box.addView(icon, lp);
        } else if (o.color != null) {
            View dot = new View(c);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            g.setColor(Ui.parseColor(o.color, Ui.color(c, R.color.primary)));
            dot.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(c, 10), Ui.dp(c, 10));
            lp.setMarginEnd(Ui.dp(c, 8));
            box.addView(dot, lp);
        }
        box.addView(label(o.label, o.muted), new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
    }

    private TextView label(String text, boolean muted) {
        TextView tv = new TextView(getContext(), null, 0, R.style.Text);
        tv.setText(text);
        tv.setMaxLines(1);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setTextColor(Ui.color(getContext(), muted ? R.color.muted_foreground : R.color.foreground));
        return tv;
    }

    private void open() {
        Context c = getContext();
        BottomSheetDialog dialog = new BottomSheetDialog(c);
        LinearLayout list = new LinearLayout(c);
        list.setOrientation(VERTICAL);
        int pad = Ui.dp(c, 16);
        list.setPadding(pad, pad, pad, pad);
        if (sheetTitle != null) {
            TextView t = new TextView(c, null, 0, R.style.Text_Section);
            t.setText(sheetTitle);
            t.setPadding(0, 0, 0, Ui.dp(c, 8));
            list.addView(t);
        }
        for (Option o : options) {
            if (o.header) {
                TextView h = new TextView(c, null, 0, R.style.Text_Label);
                h.setText(o.label);
                h.setPadding(0, Ui.dp(c, 12), 0, Ui.dp(c, 4));
                list.addView(h);
                continue;
            }
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(Ui.dp(c, 48));
            row.setBackgroundResource(android.R.drawable.list_selector_background);
            fill(row, o);
            boolean sel = o.key == null ? value == null : o.key.equals(value);
            if (sel) {
                ImageView check = new ImageView(c);
                check.setImageResource(R.drawable.ic_check);
                check.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.color(c, R.color.primary)));
                row.addView(check, new LinearLayout.LayoutParams(Ui.dp(c, 18), Ui.dp(c, 18)));
            }
            row.setOnClickListener(v -> {
                dialog.dismiss();
                value = o.key;
                render();
                if (listener != null) {
                    listener.accept(o.key);
                }
            });
            list.addView(row);
        }
        ScrollView scroll = new ScrollView(c);
        scroll.addView(list, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.setContentView(scroll);
        dialog.show();
    }
}
