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
import br.com.contaslt.dto.MonthTotal;
import br.com.contaslt.ui.Format;
import br.com.contaslt.ui.Ui;

/**
 * Receitas e despesas dos últimos meses (HistoryChart.vue): duas barras por mês, mês selecionado com fundo
 * destacado, toque seleciona o mês.
 */
public class HistoryChartView extends View {

    private static final float H = 160f;
    private static final float PAD_TOP = 12f;
    private static final float PAD_BOTTOM = 26f;

    private List<MonthTotal> history = new ArrayList<>();
    private String selected;
    private Consumer<String> onSelect;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public HistoryChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(Ui.dp(context, 11));
        text.setTypeface(ResourcesCompat.getFont(context, R.font.manrope));
        text.setFakeBoldText(true);
    }

    public void setData(List<MonthTotal> history, String selected, Consumer<String> onSelect) {
        this.history = history == null ? new ArrayList<>() : history;
        this.selected = selected;
        this.onSelect = onSelect;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(w, Ui.dp(getContext(), H));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Context c = getContext();
        float d = Ui.dp(c, 1);
        float w = getWidth();
        float h = getHeight();
        float padTop = PAD_TOP * d;
        float padBottom = PAD_BOTTOM * d;
        float plot = h - padTop - padBottom;
        int n = Math.max(1, history.size());
        float group = w / n;
        float bar = Math.min(22 * d, group * 0.28f);
        long max = 1;
        for (MonthTotal m : history) {
            max = Math.max(max, Math.max(m.income, m.expense));
        }
        paint.setColor(Ui.color(c, R.color.border));
        canvas.drawRect(0, h - padBottom, w, h - padBottom + d, paint);
        for (int i = 0; i < history.size(); i++) {
            MonthTotal m = history.get(i);
            float x = i * group;
            boolean sel = m.month.equals(selected);
            if (sel) {
                paint.setColor((Ui.color(c, R.color.accent) & 0x00FFFFFF) | 0xB3000000);
                rect.set(x + 4 * d, padTop - 6 * d, x + group - 4 * d, padTop + plot + 6 * d);
                canvas.drawRoundRect(rect, 8 * d, 8 * d, paint);
            }
            drawBar(canvas, x + group / 2 - bar - d, bar, m.income, max, plot, h - padBottom, R.color.income, d);
            drawBar(canvas, x + group / 2 + d, bar, m.expense, max, plot, h - padBottom, R.color.expense, d);
            text.setColor(Ui.color(c, sel ? R.color.foreground : R.color.muted_foreground));
            canvas.drawText(Format.monthShortLabel(m.month), x + group / 2, h - 8 * d, text);
        }
    }

    private void drawBar(Canvas canvas, float x, float width, long v, long max, float plot, float base, int color, float d) {
        if (v <= 0) {
            return;
        }
        float bh = Math.max(3 * d, (float) v / max * plot);
        paint.setColor(Ui.color(getContext(), color));
        rect.set(x, base - bh, x + width, base);
        canvas.drawRoundRect(rect, 3 * d, 3 * d, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP && !history.isEmpty()) {
            int i = (int) (e.getX() / (getWidth() / (float) history.size()));
            if (i >= 0 && i < history.size() && onSelect != null) {
                onSelect.accept(history.get(i).month);
            }
            performClick();
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
