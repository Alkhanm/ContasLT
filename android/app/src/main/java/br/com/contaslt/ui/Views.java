package br.com.contaslt.ui;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import br.com.contaslt.R;
import br.com.contaslt.databinding.ViewEmptyStateBinding;
import br.com.contaslt.databinding.ViewSectionTitleBinding;

/** Montagem dos componentes repetidos das telas (SectionTitle, EmptyState, divisórias). */
public final class Views {

    private Views() {
    }

    public static void section(ViewSectionTitleBinding b, String title, String hint, String action, View.OnClickListener onAction) {
        b.sectionTitle.setText(title);
        b.sectionHint.setText(hint);
        Ui.visible(b.sectionHint, hint != null);
        b.sectionActionText.setText(action);
        Ui.visible(b.sectionAction, action != null);
        b.sectionAction.setOnClickListener(onAction);
    }

    public static View empty(ViewGroup parent, int icon, String title, String text, String action, View.OnClickListener onAction) {
        ViewEmptyStateBinding b = ViewEmptyStateBinding.inflate(android.view.LayoutInflater.from(parent.getContext()), parent, false);
        Ui.visible(b.emptyIconWrap, icon != 0);
        if (icon != 0) {
            b.emptyIcon.setImageResource(icon);
        }
        b.emptyTitle.setText(title);
        b.emptyText.setText(text);
        Ui.visible(b.emptyText, text != null);
        b.emptyAction.setText(action);
        Ui.visible(b.emptyAction, action != null);
        b.emptyAction.setOnClickListener(onAction);
        return b.getRoot();
    }

    /** Linha de 1dp com a cor de borda entre itens de um cartão (divide-y). */
    public static View divider(ViewGroup parent) {
        View v = new View(parent.getContext());
        v.setBackgroundColor(Ui.color(parent.getContext(), R.color.border));
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(parent.getContext(), 1)));
        return v;
    }
}
