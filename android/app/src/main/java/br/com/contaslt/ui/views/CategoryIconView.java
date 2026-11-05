package br.com.contaslt.ui.views;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import br.com.contaslt.R;
import br.com.contaslt.ui.Icons;
import br.com.contaslt.ui.Ui;

/**
 * Círculo com o ícone da categoria (CategoryIcon.vue): fundo na cor da categoria com 13% de opacidade e ícone na
 * cor cheia; sem cor, fundo "muted". Tamanhos sm (28dp), md (40dp, padrão) e lg (48dp).
 */
public class CategoryIconView extends FrameLayout {

    private final ImageView icon;
    private final GradientDrawable bg = new GradientDrawable();
    private int sizeDp = 40;

    public CategoryIconView(Context context) {
        this(context, null);
    }

    public CategoryIconView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        bg.setShape(GradientDrawable.OVAL);
        setBackground(bg);
        icon = new ImageView(context);
        addView(icon, new LayoutParams(Ui.dp(context, 18), Ui.dp(context, 18), Gravity.CENTER));
        bind(null, null);
    }

    public void setSize(String size) {
        sizeDp = "sm".equals(size) ? 28 : "lg".equals(size) ? 48 : 40;
        int iconDp = "sm".equals(size) ? 14 : "lg".equals(size) ? 20 : 18;
        LayoutParams lp = (LayoutParams) icon.getLayoutParams();
        lp.width = lp.height = Ui.dp(getContext(), iconDp);
        icon.setLayoutParams(lp);
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int px = Ui.dp(getContext(), sizeDp);
        super.onMeasure(MeasureSpec.makeMeasureSpec(px, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(px, MeasureSpec.EXACTLY));
    }

    public void bind(String iconName, String color) {
        Context c = getContext();
        icon.setImageResource(Icons.forName(c, iconName));
        int muted = Ui.color(c, R.color.muted);
        int mutedFg = Ui.color(c, R.color.muted_foreground);
        int fg = Ui.parseColor(color, mutedFg);
        bg.setColor(color == null ? muted : (fg & 0x00FFFFFF) | 0x22000000);
        icon.setImageTintList(ColorStateList.valueOf(fg));
    }

    /** Transferência: setas em fundo "muted". */
    public void bindTransfer() {
        Context c = getContext();
        icon.setImageResource(R.drawable.ic_arrow_left_right);
        bg.setColor(Ui.color(c, R.color.muted));
        icon.setImageTintList(ColorStateList.valueOf(Ui.color(c, R.color.muted_foreground)));
    }
}
