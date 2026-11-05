package br.com.contaslt.ui.views;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import br.com.contaslt.R;
import br.com.contaslt.ui.Icons;
import br.com.contaslt.ui.Ui;

/** Seletor de ícone da categoria: os grupos de iconGroups (lib/icons.ts), 8 por linha, o escolhido em destaque. */
public class IconPickerView extends LinearLayout {

    /** Grupos do seletor, em ordem de exibição (nome Lucide em kebab-case, gravado na categoria). */
    public static final String[][] GROUPS = {
        {"Casa", "house", "building-2", "bed", "sofa", "lamp", "plug-zap", "droplets", "flame", "wifi", "key-round", "hammer", "wrench", "paintbrush", "trees", "flower-2", "lock"},
        {"Alimentação", "utensils", "utensils-crossed", "coffee", "pizza", "beef", "apple", "cake-slice", "wine", "beer", "ice-cream-cone", "cookie", "salad", "sandwich", "egg-fried", "cup-soda", "shopping-basket", "shopping-cart", "chef-hat"},
        {"Transporte", "car", "car-front", "bus", "bike", "fuel", "plane", "train-front", "ship", "truck", "circle-parking", "map-pin", "navigation", "footprints", "car-taxi-front"},
        {"Saúde", "heart-pulse", "stethoscope", "pill", "syringe", "activity", "dumbbell", "brain", "eye", "smile", "hospital", "cross", "ambulance"},
        {"Educação", "graduation-cap", "book", "book-open", "library", "pencil", "pen-tool", "school", "notebook-pen", "backpack"},
        {"Lazer", "tv", "popcorn", "gamepad-2", "music", "film", "clapperboard", "ticket", "camera", "palette", "tent", "mountain", "waves", "volleyball", "dices", "party-popper", "guitar", "headphones", "trophy", "medal"},
        {"Compras", "shopping-bag", "shirt", "watch", "gem", "glasses", "scissors", "sparkles", "spray-can", "package", "gift", "store"},
        {"Família", "baby", "dog", "cat", "paw-print", "users", "user", "heart", "hand-heart", "church"},
        {"Finanças", "wallet", "banknote", "piggy-bank", "credit-card", "landmark", "hand-coins", "trending-up", "trending-down", "chart-line", "percent", "coins", "receipt", "bitcoin", "calculator", "badge-dollar-sign", "circle-dollar-sign", "scale"},
        {"Trabalho", "briefcase", "laptop", "smartphone", "monitor", "printer", "cloud", "server", "code", "cpu", "phone", "mail"},
        {"Serviços", "repeat", "calendar", "clock", "file-text", "newspaper", "shield", "umbrella", "zap", "globe", "radio", "rocket"},
        {"Outros", "tag", "tags", "star", "circle-plus", "circle-ellipsis", "circle-dashed", "flag", "bookmark", "box", "archive", "leaf", "sun", "moon", "snowflake", "cloud-rain"},
    };

    private static final int COLUMNS = 8;

    private final Map<String, FrameLayout> cells = new HashMap<>();
    private final Map<String, ImageView> icons = new HashMap<>();
    private String value = "tag";
    private Consumer<String> listener;

    public IconPickerView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        int gap = Ui.dp(context, 6);
        for (int g = 0; g < GROUPS.length; g++) {
            String[] group = GROUPS[g];
            TextView label = new TextView(context, null, 0, R.style.Text_Label);
            label.setText(group[0]);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            LayoutParams llp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            llp.topMargin = g == 0 ? 0 : Ui.dp(context, 8);
            llp.bottomMargin = Ui.dp(context, 4);
            addView(label, llp);
            LinearLayout row = null;
            int count = group.length - 1;
            int rows = (count + COLUMNS - 1) / COLUMNS;
            for (int i = 0; i < rows * COLUMNS; i++) {
                if (i % COLUMNS == 0) {
                    row = new LinearLayout(context);
                    LayoutParams rlp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
                    if (i > 0) {
                        rlp.topMargin = gap;
                    }
                    addView(row, rlp);
                }
                LayoutParams lp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
                if (i % COLUMNS > 0) {
                    lp.setMarginStart(gap);
                }
                ColorPaletteView.SquareFrame cell = new ColorPaletteView.SquareFrame(context);
                row.addView(cell, lp);
                if (i >= count) {
                    continue;
                }
                String name = group[i + 1];
                ImageView icon = new ImageView(context);
                icon.setImageResource(Icons.forName(context, name));
                cell.addView(icon, new FrameLayout.LayoutParams(Ui.dp(context, 16), Ui.dp(context, 16), Gravity.CENTER));
                cell.setContentDescription(name);
                cell.setOnClickListener(v -> {
                    setValue(name);
                    if (listener != null) {
                        listener.accept(name);
                    }
                });
                cells.put(name, cell);
                icons.put(name, icon);
            }
        }
        render();
    }

    public void setOnChange(Consumer<String> l) {
        listener = l;
    }

    public String value() {
        return value;
    }

    public void setValue(String name) {
        value = name;
        render();
    }

    private void render() {
        Context c = getContext();
        int primary = Ui.color(c, R.color.primary);
        int border = Ui.color(c, R.color.border);
        int muted = Ui.color(c, R.color.muted_foreground);
        for (Map.Entry<String, FrameLayout> e : cells.entrySet()) {
            boolean sel = e.getKey().equals(value);
            e.getValue().setBackground(UiKit.box(c, sel ? UiKit.alpha(primary, 0.1f) : 0, sel ? primary : border, 10, 1));
            icons.get(e.getKey()).setImageTintList(ColorStateList.valueOf(sel ? primary : muted));
        }
    }
}
