package br.com.contaslt.ui.views;

import java.util.ArrayList;
import java.util.List;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import br.com.contaslt.R;
import br.com.contaslt.dto.ItemPurchase;
import br.com.contaslt.ui.Format;
import br.com.contaslt.ui.Ui;

/**
 * PriceLine.vue: linha do preço unitário de um item ao longo das compras. Compacta (sparkline 96×28dp) ou
 * detalhada (110dp de altura, datas nas pontas e o valor em cada ponto quando são poucas compras; tocar mostra o
 * valor e a data do ponto).
 */
public class PriceLineView extends View {

    private static final int LABEL_EVERY_POINT_UP_TO = 8;

    private List<ItemPurchase> purchases = new ArrayList<>();
    private Integer color;
    private boolean detailed;
    private int touched = -1;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public PriceLineView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeCap(Paint.Cap.ROUND);
        text.setTypeface(ResourcesCompat.getFont(context, R.font.manrope));
    }

    public PriceLineView(Context context) {
        this(context, null);
    }

    public void setData(List<ItemPurchase> purchases, String colorHex, boolean detailed) {
        this.purchases = purchases == null ? new ArrayList<>() : purchases;
        this.color = colorHex == null ? null : Ui.parseColor(colorHex, Ui.color(getContext(), R.color.primary));
        this.detailed = detailed;
        touched = -1;
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        Context c = getContext();
        if (detailed) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), Ui.dp(c, 110));
        } else {
            setMeasuredDimension(Ui.dp(c, 96), Ui.dp(c, 28));
        }
    }

    private static long price(ItemPurchase p) {
        return p.unitPrice != null ? p.unitPrice : p.total;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int n = purchases.size();
        if (n == 0) {
            return;
        }
        Context c = getContext();
        float d = Ui.dp(c, 1);
        float w = getWidth();
        float h = getHeight();
        float padX = (detailed ? 8 : 2) * d;
        float padTop = (detailed ? 16 : 3) * d;
        float padBottom = (detailed ? 24 : 3) * d;
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (ItemPurchase p : purchases) {
            min = Math.min(min, price(p));
            max = Math.max(max, price(p));
        }
        float span = max - min;
        float plot = h - padTop - padBottom;
        float[] xs = new float[n];
        float[] ys = new float[n];
        for (int i = 0; i < n; i++) {
            long v = price(purchases.get(i));
            xs[i] = n == 1 ? w / 2 : padX + (i / (float) (n - 1)) * (w - padX * 2);
            ys[i] = span == 0 ? padTop + plot / 2 : padTop + plot - ((v - min) / span) * plot;
        }
        int stroke = color != null ? color : Ui.color(c, R.color.primary);
        int fg = Ui.color(c, R.color.foreground);
        int mutedFg = Ui.color(c, R.color.muted_foreground);

        if (detailed) {
            line.setColor(Ui.color(c, R.color.border));
            line.setStrokeWidth(d);
            float y = h - padBottom + 6 * d;
            canvas.drawLine(padX, y, w - padX, y, line);
        }
        path.reset();
        for (int i = 0; i < n; i++) {
            if (i == 0) {
                path.moveTo(xs[i], ys[i]);
            } else {
                path.lineTo(xs[i], ys[i]);
            }
        }
        line.setColor(stroke);
        line.setStrokeWidth(2 * d);
        canvas.drawPath(path, line);
        dot.setStyle(Paint.Style.FILL);
        dot.setColor(stroke);
        boolean labelled = detailed && n <= LABEL_EVERY_POINT_UP_TO;
        text.setTextSize(10 * d);
        for (int i = 0; i < n; i++) {
            canvas.drawCircle(xs[i], ys[i], (detailed ? 3.5f : 2f) * d, dot);
            if (labelled) {
                boolean last = i == n - 1;
                text.setColor(last ? fg : mutedFg);
                text.setFakeBoldText(last);
                text.setTextAlign(Paint.Align.CENTER);
                float x = Math.min(w - 24 * d, Math.max(24 * d, xs[i]));
                canvas.drawText(Format.moneyPlain(price(purchases.get(i))), x, ys[i] - 7 * d, text);
            }
        }
        if (!detailed) {
            return;
        }
        text.setFakeBoldText(false);
        text.setColor(mutedFg);
        if (n > 1) {
            text.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(Format.dateShort(purchases.get(0).date), padX, h - 4 * d, text);
        }
        text.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(Format.dateShort(purchases.get(n - 1).date), w - padX, h - 4 * d, text);

        if (!labelled && touched >= 0 && touched < n) {
            float x = xs[touched];
            float y = ys[touched];
            dot.setStyle(Paint.Style.STROKE);
            dot.setStrokeWidth(1.5f * d);
            canvas.drawCircle(x, y, 5.5f * d, dot);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(11 * d);
            text.setFakeBoldText(true);
            text.setColor(fg);
            String s = Format.money(price(purchases.get(touched))) + " · " + Format.dateShort(purchases.get(touched).date);
            canvas.drawText(s, Math.min(w - 60 * d, Math.max(60 * d, x)), y < padTop + 20 * d ? y + 18 * d : y - 10 * d, text);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int n = purchases.size();
        if (!detailed || n <= LABEL_EVERY_POINT_UP_TO) {
            return super.onTouchEvent(e);
        }
        int i = (int) Math.max(0, Math.min(n - 1, e.getX() / (getWidth() / (float) n)));
        if (i != touched) {
            touched = i;
            invalidate();
        }
        if (e.getAction() == MotionEvent.ACTION_MOVE) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (e.getAction() == MotionEvent.ACTION_UP) {
            performClick();
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
