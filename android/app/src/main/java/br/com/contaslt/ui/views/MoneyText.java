package br.com.contaslt.ui.views;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import br.com.contaslt.R;
import br.com.contaslt.ui.Format;
import br.com.contaslt.ui.Ui;

/**
 * Componente Money do PWA dentro de um texto corrido ("Saldo <b>R$ 1.234,56</b>", "previsto −R$ 3,50"): mesmo
 * sinal e cor de {@link Ui#money}, com destaque em negrito.
 */
public final class MoneyText {

    private MoneyText() {
    }

    /** Texto do valor: tipo EXPENSE/INCOME/null, com ou sem sinal (regras do Money.vue). */
    public static String text(long cents, String type, boolean signed) {
        String abs = Format.money(Math.abs(cents));
        if (!signed) {
            return type == null && cents < 0 ? "−" + abs : abs;
        }
        if ("EXPENSE".equals(type)) {
            return "−" + abs;
        }
        if ("INCOME".equals(type)) {
            return "+" + abs;
        }
        return cents < 0 ? "−" + abs : abs;
    }

    public static int color(Context c, long cents, String type, boolean muted) {
        if (muted) {
            return Ui.color(c, R.color.muted_foreground);
        }
        if ("EXPENSE".equals(type) || (type == null && cents < 0)) {
            return Ui.color(c, R.color.expense);
        }
        if ("INCOME".equals(type)) {
            return Ui.color(c, R.color.income);
        }
        return Ui.color(c, R.color.foreground);
    }

    /** Acrescenta o valor colorido e em negrito. */
    public static SpannableStringBuilder append(SpannableStringBuilder sb, Context c, long cents, String type, boolean signed,
            boolean muted, boolean bold) {
        int start = sb.length();
        sb.append(text(cents, type, signed));
        sb.setSpan(new ForegroundColorSpan(color(c, cents, type, muted)), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) {
            sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    /** Trecho em negrito, sem cor. */
    public static SpannableStringBuilder strong(SpannableStringBuilder sb, String text) {
        int start = sb.length();
        sb.append(text);
        sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    /** Trecho com cor de recurso. */
    public static SpannableStringBuilder colored(SpannableStringBuilder sb, Context c, String text, int colorRes) {
        int start = sb.length();
        sb.append(text);
        sb.setSpan(new ForegroundColorSpan(Ui.color(c, colorRes)), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }
}
