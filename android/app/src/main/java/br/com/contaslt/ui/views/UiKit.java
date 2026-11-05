package br.com.contaslt.ui.views;

import java.util.Map;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import br.com.contaslt.R;
import br.com.contaslt.ui.Ui;

/**
 * Peças pequenas repetidas nas telas de cadastro e relatórios: textos com estilo, caixas arredondadas (cartões
 * rounded-xl, avisos bg-primary/5), botões só de ícone (ghost icon-sm), ConfirmDialog.vue e os avisos (toast).
 */
public final class UiKit {

    private UiKit() {
    }

    public static TextView text(Context c, int style, CharSequence s) {
        TextView tv = new TextView(c, null, 0, style);
        tv.setText(s);
        return tv;
    }

    /** Texto com tamanho em sp diferente do estilo (ex.: text-[11px]). */
    public static TextView text(Context c, int style, CharSequence s, float sp) {
        TextView tv = text(c, style, s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return tv;
    }

    /** Caixa arredondada: fundo (cor já resolvida, ou 0 = transparente), borda opcional. */
    public static GradientDrawable box(Context c, int fill, int stroke, float radiusDp, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(Ui.dp(c, radiusDp));
        g.setColor(fill);
        if (strokeDp > 0) {
            g.setStroke(Math.max(1, Math.round(strokeDp * c.getResources().getDisplayMetrics().density)), stroke);
        }
        return g;
    }

    /** Cartão rounded-xl (12dp) com borda, como as linhas de Contas e Categorias. */
    public static GradientDrawable cardXl(Context c) {
        return box(c, Ui.color(c, R.color.card), Ui.color(c, R.color.border), 12, 1);
    }

    /** Aviso "bg-primary/5" arredondado (rounded-lg). */
    public static GradientDrawable primarySoft(Context c) {
        return box(c, alpha(Ui.color(c, R.color.primary), 0.07f), 0, 12, 0);
    }

    public static int alpha(int color, float a) {
        return (color & 0x00FFFFFF) | (Math.round(a * 255) << 24);
    }

    /** Botão ghost só com ícone (size icon-sm do PWA): 32dp, ícone 16dp. */
    public static ImageView iconButton(Context c, int icon, int tintRes, String desc, View.OnClickListener l) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(icon);
        iv.setImageTintList(ColorStateList.valueOf(Ui.color(c, tintRes)));
        int p = Ui.dp(c, 8);
        iv.setPadding(p, p, p, p);
        TypedValue tv = new TypedValue();
        c.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        iv.setBackgroundResource(tv.resourceId);
        iv.setContentDescription(desc);
        iv.setTooltipText(desc);
        iv.setOnClickListener(l);
        iv.setLayoutParams(new ViewGroup.LayoutParams(Ui.dp(c, 36), Ui.dp(c, 36)));
        return iv;
    }

    /** ConfirmDialog.vue: título, descrição, Cancelar e a ação (vermelha se destrutiva). */
    public static void confirm(Context c, String title, String message, String label, boolean destructive, Runnable onConfirm) {
        AlertDialog d = new MaterialAlertDialogBuilder(c)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton(label, (dialog, which) -> onConfirm.run())
            .create();
        d.show();
        if (destructive) {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Ui.color(c, R.color.destructive));
        }
    }

    /** notifySuccess/notifyError do PWA: aviso no rodapé da tela. */
    public static void toast(Activity a, String msg) {
        if (a == null) {
            return;
        }
        View root = a.findViewById(android.R.id.content);
        if (root != null) {
            Snackbar.make(root, msg, Snackbar.LENGTH_LONG).show();
        }
    }

    /** Aviso preso a uma vista (ex.: dentro de um diálogo aberto). */
    public static void snack(View anchor, String msg) {
        if (anchor != null && msg != null) {
            Snackbar.make(anchor, msg, Snackbar.LENGTH_LONG).show();
        }
    }

    /** Habilita/desabilita um grupo inteiro (ex.: TypeToggle :disabled), com a opacidade do PWA. */
    public static void setEnabledDeep(View v, boolean enabled) {
        v.setEnabled(enabled);
        if (v instanceof ViewGroup g) {
            for (int i = 0; i < g.getChildCount(); i++) {
                setEnabledDeep(g.getChildAt(i), enabled);
            }
        }
    }

    /** Erros de campo (fieldErrors do PWA) nos TextViews de erro de cada campo; os demais somem. */
    public static void showErrors(Map<String, String> errors, Map<String, TextView> fields) {
        for (Map.Entry<String, TextView> e : fields.entrySet()) {
            String msg = errors == null ? null : errors.get(e.getKey());
            e.getValue().setText(msg);
            Ui.visible(e.getValue(), msg != null);
        }
    }

    public static void bold(SpannableStringBuilder s, String text) {
        int start = s.length();
        s.append(text);
        s.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    public static void colored(SpannableStringBuilder s, String text, int color, boolean bold) {
        int start = s.length();
        s.append(text);
        s.setSpan(new ForegroundColorSpan(color), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) {
            s.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    /** "1 lançamento" / "2 lançamentos". */
    public static String plural(long n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    /** Janela do Dialog do PWA no celular: cartão com fundo "background", raio 16dp e 16dp de margem nas laterais. */
    public static void cardDialogWindow(android.app.Dialog d) {
        android.view.Window w = d.getWindow();
        if (w == null) {
            return;
        }
        Context c = d.getContext();
        GradientDrawable bg = box(c, Ui.color(c, R.color.background), Ui.color(c, R.color.border), 16, 1);
        w.setBackgroundDrawable(new android.graphics.drawable.InsetDrawable(bg, Ui.dp(c, 16), Ui.dp(c, 24), Ui.dp(c, 16), Ui.dp(c, 24)));
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /**
     * Bottom sheet do FormSheet.vue: aberta por inteiro (sem estado recolhido), cantos superiores de 16dp, fundo
     * "background" e altura de {@code fraction} da tela (max-h-[92dvh]).
     */
    public static void fullSheet(android.app.Dialog d, float fraction) {
        if (!(d instanceof com.google.android.material.bottomsheet.BottomSheetDialog bsd)) {
            return;
        }
        View sheet = bsd.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet == null) {
            return;
        }
        Context c = d.getContext();
        GradientDrawable bg = new GradientDrawable();
        float r = Ui.dp(c, 16);
        bg.setCornerRadii(new float[] {r, r, r, r, 0, 0, 0, 0});
        bg.setColor(Ui.color(c, R.color.background));
        sheet.setBackground(bg);
        sheet.setClipToOutline(true);
        ViewGroup.LayoutParams lp = sheet.getLayoutParams();
        lp.height = Math.round(c.getResources().getDisplayMetrics().heightPixels * fraction);
        sheet.setLayoutParams(lp);
        bsd.getBehavior().setSkipCollapsed(true);
        bsd.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
    }

    /** Cor do tipo de lançamento para o TypeToggle: Despesa vermelha, Receita verde, texto branco. */
    public static void bindTypeToggle(SegmentedControl s, String type) {
        Context c = s.getContext();
        s.setValue(type);
        s.setActiveColor(Ui.color(c, "INCOME".equals(type) ? R.color.income : R.color.expense), 0xFFFFFFFF);
    }
}
