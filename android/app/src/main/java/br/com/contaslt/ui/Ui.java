package br.com.contaslt.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import br.com.contaslt.R;

/** Auxiliares de interface: dp, cores do tema e o valor colorido do componente Money do PWA. */
public final class Ui {

    private Ui() {
    }

    public static int dp(Context ctx, float dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, ctx.getResources().getDisplayMetrics()));
    }

    public static int color(Context ctx, int res) {
        return ContextCompat.getColor(ctx, res);
    }

    /** "#RRGGBB" da categoria/conta; cor padrão se inválida. */
    public static int parseColor(String hex, int fallback) {
        try {
            return hex == null ? fallback : Color.parseColor(hex.length() == 9 ? "#" + hex.substring(7) + hex.substring(1, 7) : hex);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** Componente Money: tipo EXPENSE/INCOME/null (neutro), com ou sem sinal, apagado. */
    public static void money(TextView tv, long cents, String type, boolean signed, boolean muted) {
        Context c = tv.getContext();
        String abs = Format.money(Math.abs(cents));
        String text;
        if (!signed) {
            text = type == null && cents < 0 ? "−" + abs : abs;
        } else if ("EXPENSE".equals(type)) {
            text = "−" + abs;
        } else if ("INCOME".equals(type)) {
            text = "+" + abs;
        } else {
            text = cents < 0 ? "−" + abs : abs;
        }
        tv.setText(text);
        int color;
        if (muted) {
            color = R.color.muted_foreground;
        } else if ("EXPENSE".equals(type)) {
            color = R.color.expense;
        } else if ("INCOME".equals(type)) {
            color = R.color.income;
        } else if (cents < 0) {
            color = R.color.expense;
        } else {
            color = R.color.foreground;
        }
        tv.setTextColor(color(c, color));
    }

    public static void tint(View v, int colorRes) {
        v.setBackgroundTintList(ColorStateList.valueOf(color(v.getContext(), colorRes)));
    }

    public static void visible(View v, boolean show) {
        v.setVisibility(show ? View.VISIBLE : View.GONE);
    }
}
