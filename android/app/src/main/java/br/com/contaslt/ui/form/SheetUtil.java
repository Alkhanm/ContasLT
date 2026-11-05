package br.com.contaslt.ui.form;

import android.app.Activity;
import android.app.Dialog;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.snackbar.Snackbar;

import br.com.contaslt.R;

/** FormSheet.vue no celular: sheet de baixo, aberta inteira (até 92% da tela), e os avisos (toast do PWA). */
public final class SheetUtil {

    private SheetUtil() {
    }

    /** Abre expandida, sem o estado recolhido; {@code fullHeight} = altura fixa de 92% (formulários longos). */
    public static void expand(Dialog dialog, boolean fullHeight) {
        if (!(dialog instanceof BottomSheetDialog d)) {
            return;
        }
        if (d.getWindow() != null) {
            d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        View sheet = d.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet == null) {
            return;
        }
        sheet.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        int screen = sheet.getResources().getDisplayMetrics().heightPixels;
        ViewGroup.LayoutParams lp = sheet.getLayoutParams();
        lp.height = fullHeight ? (int) (screen * 0.92f) : ViewGroup.LayoutParams.WRAP_CONTENT;
        sheet.setLayoutParams(lp);
        BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(sheet);
        behavior.setSkipCollapsed(true);
        behavior.setMaxHeight((int) (screen * 0.92f));
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
    }

    /** Aviso na tela principal (depois de fechar a sheet), acima da barra de navegação. */
    public static void snack(Activity activity, String message) {
        if (activity == null) {
            return;
        }
        View root = activity.findViewById(R.id.root);
        if (root == null) {
            root = activity.findViewById(android.R.id.content);
        }
        if (root == null) {
            return;
        }
        Snackbar s = Snackbar.make(root, message, Snackbar.LENGTH_LONG);
        View nav = activity.findViewById(R.id.bottomNav);
        if (nav != null && nav.getVisibility() == View.VISIBLE) {
            s.setAnchorView(nav);
        }
        s.show();
    }

    /** Aviso dentro da sheet aberta. */
    public static void snack(View inSheet, String message) {
        if (inSheet != null) {
            Snackbar.make(inSheet, message, Snackbar.LENGTH_LONG).show();
        }
    }
}
