package br.com.contaslt.ui.views;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import br.com.contaslt.R;
import br.com.contaslt.ui.Ui;

/** Seletor de cor das contas e categorias (grade 8×5 do PWA, colorPalette de lib/icons.ts), com ✓ na escolhida. */
public class ColorPaletteView extends LinearLayout {

    /** 40 cores em 5 linhas de 8: verdes, azuis, roxos/rosas, vermelhos/laranjas/amarelos, marrons/cinzas. */
    public static final String[] PALETTE = {
        "#14532D", "#1F5F43", "#2E8B57", "#3D8361", "#4D9A5A", "#15803D", "#65A30D", "#4F7942",
        "#0F766E", "#0E9488", "#0891B2", "#0284C7", "#3A6EA5", "#2563EB", "#1E40AF", "#4F46E5",
        "#6B4FBB", "#8A5CF6", "#7C3AED", "#9333EA", "#A21CAF", "#C026D3", "#BE185D", "#B5537A",
        "#DC2626", "#C2452D", "#B91C1C", "#EA580C", "#D9622B", "#F59E0B", "#C98A2B", "#E0A526",
        "#92400E", "#78350F", "#8B5E3C", "#A16207", "#6B7280", "#4B5563", "#374151", "#9CA3AF",
    };

    private static final int COLUMNS = 8;

    private final List<ImageView> checks = new ArrayList<>();
    private String value = PALETTE[0];
    private Consumer<String> listener;

    public ColorPaletteView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        int gap = Ui.dp(context, 8);
        LinearLayout row = null;
        for (int i = 0; i < PALETTE.length; i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(context);
                row.setOrientation(HORIZONTAL);
                LayoutParams rlp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
                if (i > 0) {
                    rlp.topMargin = gap;
                }
                addView(row, rlp);
            }
            String color = PALETTE[i];
            SquareFrame cell = new SquareFrame(context);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(Ui.parseColor(color, 0));
            cell.setBackground(bg);
            cell.setContentDescription(color);
            ImageView check = new ImageView(context);
            check.setImageResource(R.drawable.ic_check);
            check.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
            cell.addView(check, new FrameLayout.LayoutParams(Ui.dp(context, 16), Ui.dp(context, 16), Gravity.CENTER));
            checks.add(check);
            cell.setOnClickListener(v -> {
                setValue(color);
                if (listener != null) {
                    listener.accept(color);
                }
            });
            LayoutParams lp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
            if (i % COLUMNS > 0) {
                lp.setMarginStart(gap);
            }
            row.addView(cell, lp);
        }
        render();
    }

    public void setOnChange(Consumer<String> l) {
        listener = l;
    }

    public String value() {
        return value;
    }

    public void setValue(String color) {
        value = color;
        render();
    }

    private void render() {
        for (int i = 0; i < PALETTE.length; i++) {
            Ui.visible(checks.get(i), PALETTE[i].equalsIgnoreCase(value));
        }
    }

    /** Célula quadrada (aspect-square). */
    static class SquareFrame extends FrameLayout {
        SquareFrame(Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, widthMeasureSpec);
        }
    }
}
