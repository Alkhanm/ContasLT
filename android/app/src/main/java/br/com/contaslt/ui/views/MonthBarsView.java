package br.com.contaslt.ui.views;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import br.com.contaslt.R;
import br.com.contaslt.dto.MonthValue;
import br.com.contaslt.ui.Format;
import br.com.contaslt.ui.Ui;

/**
 * MonthBars.vue: barras de um valor por mês (histórico de uma categoria ou de um item) na cor dada; o mês
 * selecionado fica em destaque. Tocar mostra o valor do mês no topo e avisa {@link #setOnSelect}.
 */
public class MonthBarsView extends View {

    private static final float HEADER = 16f;
    private static final float PAD_BOTTOM = 18f;
    private static final float CHART = 72f;

    private List<MonthValue> values = new ArrayList<>();
    private Integer color;
    private String selected;
    private int touched = -1;
    private Consumer<String> onSelect;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint header = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public MonthBarsView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        android.graphics.Typeface tf = ResourcesCompat.getFont(context, R.font.manrope);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTextSize(Ui.dp(context, 11));
        label.setTypeface(tf);
        label.setFakeBoldText(true);
        header.setTextAlign(Paint.Align.RIGHT);
        header.setTextSize(Ui.dp(context, 11));
        header.setTypeface(tf);
    }

    public MonthBarsView(Context context) {
        this(context, null);
    }

    public void setData(List<MonthValue> values, String colorHex, String selected) {
        this.values = values == null ? new ArrayList<>() : values;
        this.color = colorHex == null ? null : Ui.parseColor(colorHex, Ui.color(getContext(), R.color.primary));
        this.selected = selected;
        touched = -1;
        invalidate();
    }

    public void setOnSelect(Consumer<String> l) {
        onSelect = l;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), Ui.dp(getContext(), HEADER + CHART));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Context c = getContext();
        float d = Ui.dp(c, 1);
        float w = getWidth();
        float top = HEADER * d;
        float h = CHART * d;
        float base = top + h - PAD_BOTTOM * d;
        float plot = h - PAD_BOTTOM * d - 4 * d;
        int n = Math.max(1, values.size());
        float group = w / n;
        float bar = Math.min(28f * w / 320f, group * 0.55f);
        long max = 1;
        for (MonthValue m : values) {
            max = Math.max(max, m.total);
        }
        int fg = Ui.color(c, R.color.foreground);
        int mutedFg = Ui.color(c, R.color.muted_foreground);
        int barColor = color != null ? color : Ui.color(c, R.color.primary);

        if (touched >= 0 && touched < values.size()) {
            MonthValue m = values.get(touched);
            String amount = Format.money(m.total);
            header.setColor(fg);
            header.setFakeBoldText(true);
            float y = top - 4 * d;
            canvas.drawText(amount, w, y, header);
            float aw = header.measureText(amount);
            header.setFakeBoldText(false);
            header.setColor(mutedFg);
            canvas.drawText(Format.monthShortLabel(m.month) + ": ", w - aw, y, header);
        }

        paint.setColor(Ui.color(c, R.color.border));
        canvas.drawRect(0, base, w, base + d, paint);
        for (int i = 0; i < values.size(); i++) {
            MonthValue m = values.get(i);
            float bh = m.total <= 0 ? 0 : Math.max(2 * d, (float) m.total / max * plot);
            float x = i * group + (group - bar) / 2;
            boolean dim = selected != null && !selected.equals(m.month) && touched != i;
            paint.setColor(barColor);
            paint.setAlpha(dim ? 115 : 255);
            rect.set(x, base - bh, x + bar, base);
            canvas.drawRoundRect(rect, 3 * d, 3 * d, paint);
            paint.setAlpha(255);
            label.setColor(m.month.equals(selected) ? fg : mutedFg);
            canvas.drawText(Format.monthShortLabel(m.month), i * group + group / 2, top + h - 5 * d, label);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (values.isEmpty()) {
            return false;
        }
        int i = (int) Math.max(0, Math.min(values.size() - 1, e.getX() / (getWidth() / (float) values.size())));
        if (e.getAction() == MotionEvent.ACTION_DOWN || e.getAction() == MotionEvent.ACTION_MOVE) {
            if (i != touched) {
                touched = i;
                invalidate();
            }
            getParent().requestDisallowInterceptTouchEvent(e.getAction() == MotionEvent.ACTION_MOVE);
            return true;
        }
        if (e.getAction() == MotionEvent.ACTION_UP) {
            performClick();
            if (onSelect != null) {
                onSelect.accept(values.get(i).month);
            }
            return true;
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
