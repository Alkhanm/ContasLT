package br.com.contaslt.ui.views;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import br.com.contaslt.R;
import br.com.contaslt.ui.Ui;

/**
 * Abas segmentadas do PWA (fundo "muted", aba ativa em "card"): Tudo/Despesas/Receitas, Entrar/Criar conta,
 * Despesa/Receita/Transferência. {@link #setActiveColor} pinta a ativa (ex.: Despesa em vermelho).
 */
public class SegmentedControl extends LinearLayout {

    private final List<TextView> items = new ArrayList<>();
    private final List<String> values = new ArrayList<>();
    private int selected = -1;
    private Consumer<String> listener;
    private Integer activeBg;
    private Integer activeFg;

    public SegmentedControl(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setBackgroundResource(R.drawable.bg_muted_round);
        int p = Ui.dp(context, 4);
        setPadding(p, p, p, p);
    }

    public SegmentedControl(Context context) {
        this(context, null);
    }

    public void setOptions(String[] labels, String[] keys) {
        removeAllViews();
        items.clear();
        values.clear();
        for (int i = 0; i < labels.length; i++) {
            TextView tv = new TextView(getContext(), null, 0, R.style.Text_Semibold_Sm);
            tv.setText(labels[i]);
            tv.setGravity(Gravity.CENTER);
            tv.setMinHeight(Ui.dp(getContext(), 36));
            tv.setMaxLines(1);
            int idx = i;
            tv.setOnClickListener(v -> select(idx, true));
            addView(tv, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
            items.add(tv);
            values.add(keys[i]);
        }
        select(0, false);
    }

    public void setOnChange(Consumer<String> l) {
        listener = l;
    }

    /** Cor de fundo/texto da aba ativa; null volta ao padrão (card). */
    public void setActiveColor(Integer bg, Integer fg) {
        activeBg = bg;
        activeFg = fg;
        select(selected, false);
    }

    public String value() {
        return selected < 0 ? null : values.get(selected);
    }

    public void setValue(String key) {
        int i = values.indexOf(key);
        if (i >= 0) {
            select(i, false);
        }
    }

    private void select(int idx, boolean notify) {
        boolean changed = idx != selected;
        selected = idx;
        Context c = getContext();
        for (int i = 0; i < items.size(); i++) {
            TextView tv = items.get(i);
            if (i == idx) {
                tv.setBackgroundResource(R.drawable.bg_segment_selected);
                if (activeBg != null) {
                    tv.getBackground().mutate().setTint(activeBg);
                }
                tv.setTextColor(activeFg != null ? activeFg : Ui.color(c, R.color.foreground));
                tv.setElevation(Ui.dp(c, 1));
            } else {
                tv.setBackground(null);
                tv.setTextColor(Ui.color(c, R.color.muted_foreground));
                tv.setElevation(0);
            }
        }
        if (notify && changed && listener != null) {
            listener.accept(values.get(idx));
        }
    }
}
