package br.com.contaslt.ui;

import android.content.Context;

import br.com.contaslt.R;

/** Ícones Lucide convertidos (res/drawable/ic_*.xml), pelo nome kebab-case gravado na categoria. */
public final class Icons {

    private Icons() {
    }

    public static int forName(Context ctx, String name) {
        if (name == null || name.isEmpty()) {
            return R.drawable.ic_tag;
        }
        int id = ctx.getResources().getIdentifier("ic_" + name.replace('-', '_'), "drawable", ctx.getPackageName());
        return id != 0 ? id : R.drawable.ic_tag;
    }
}
