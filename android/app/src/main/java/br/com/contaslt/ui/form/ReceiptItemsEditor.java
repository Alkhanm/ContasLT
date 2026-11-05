package br.com.contaslt.ui.form;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;

import br.com.contaslt.R;
import br.com.contaslt.dto.TransactionItemInput;
import br.com.contaslt.ui.Format;
import br.com.contaslt.ui.Ui;
import br.com.contaslt.ui.Views;

/**
 * ReceiptItemsEditor.vue: itens do comprovante (produto, quantidade e valor), soma e a diferença para o valor do
 * lançamento, com "usar a soma".
 */
public class ReceiptItemsEditor extends LinearLayout {

    private final LinearLayout list;
    private final TextView sumText;
    private List<TransactionItemInput> items = new ArrayList<>();
    private Supplier<Long> amount = () -> null;
    private Consumer<Long> onUseSum;
    private Runnable onChange;

    public ReceiptItemsEditor(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        list = new LinearLayout(context);
        list.setOrientation(VERTICAL);
        list.setBackgroundResource(R.drawable.sheet_transaction_box);
        addView(list, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        LinearLayout footer = new LinearLayout(context);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams flp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        footer.setLayoutParams(flp);
        MaterialButton add = new MaterialButton(context, null, androidx.appcompat.R.attr.borderlessButtonStyle);
        add.setText("Item");
        add.setAllCaps(false);
        add.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        add.setTextColor(Ui.color(context, R.color.foreground));
        add.setIconResource(R.drawable.ic_plus);
        add.setIconSize(Ui.dp(context, 16));
        add.setIconPadding(Ui.dp(context, 4));
        add.setIconTint(ColorStateList.valueOf(Ui.color(context, R.color.foreground)));
        add.setMinHeight(Ui.dp(context, 28));
        add.setMinimumHeight(Ui.dp(context, 28));
        add.setInsetTop(0);
        add.setInsetBottom(0);
        add.setPadding(Ui.dp(context, 8), 0, Ui.dp(context, 8), 0);
        add.setOnClickListener(v -> {
            TransactionItemInput it = new TransactionItemInput();
            it.description = "";
            it.quantity = 1.0;
            it.total = 0L;
            items.add(it);
            render();
            changed();
        });
        footer.addView(add, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        sumText = new TextView(context, null, 0, R.style.Text_Muted_Xs);
        sumText.setGravity(Gravity.END);
        sumText.setOnClickListener(v -> {
            long diff = difference();
            if (diff != 0 && onUseSum != null) {
                onUseSum.accept(sum());
            }
        });
        LayoutParams slp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
        slp.setMarginStart(Ui.dp(context, 12));
        footer.addView(sumText, slp);
        LayoutParams top = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        top.topMargin = Ui.dp(context, 8);
        addView(footer, top);
        render();
    }

    public void setItems(List<TransactionItemInput> items) {
        this.items = items;
        render();
    }

    public List<TransactionItemInput> items() {
        return items;
    }

    /** Valor do lançamento, para mostrar quanto falta ou excede. */
    public void setAmountSource(Supplier<Long> amount) {
        this.amount = amount;
    }

    public void setOnUseSum(Consumer<Long> l) {
        onUseSum = l;
    }

    /** Quantidade de itens mudou (título "Itens (n)"). */
    public void setOnChange(Runnable l) {
        onChange = l;
    }

    private void changed() {
        if (onChange != null) {
            onChange.run();
        }
    }

    private long sum() {
        long s = 0;
        for (TransactionItemInput i : items) {
            s += i.total == null ? 0 : i.total;
        }
        return s;
    }

    private long difference() {
        Long a = amount.get();
        return a == null ? 0 : a - sum();
    }

    /** Atualiza só a linha da soma (o valor do lançamento mudou). */
    public void refreshSum() {
        Context c = getContext();
        Ui.visible(sumText, !items.isEmpty());
        if (items.isEmpty()) {
            return;
        }
        SpannableStringBuilder s = new SpannableStringBuilder("Soma ");
        int start = s.length();
        s.append(Format.money(sum()));
        s.setSpan(new ForegroundColorSpan(Ui.color(c, R.color.foreground)), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        long diff = difference();
        if (diff != 0) {
            s.append(" · ");
            start = s.length();
            s.append(diff > 0 ? "faltam " : "excedem ").append(Format.money(Math.abs(diff)));
            s.setSpan(new ForegroundColorSpan(Ui.color(c, R.color.warning)), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.append(" ");
            start = s.length();
            s.append("usar a soma");
            s.setSpan(new ForegroundColorSpan(Ui.color(c, R.color.primary)), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        sumText.setText(s);
    }

    private void render() {
        Context c = getContext();
        list.removeAllViews();
        Ui.visible(list, !items.isEmpty());
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                list.addView(Views.divider(list));
            }
            list.addView(row(c, i, items.get(i)));
        }
        refreshSum();
    }

    private LinearLayout row(Context c, int index, TransactionItemInput item) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(VERTICAL);
        int p = Ui.dp(c, 8);
        row.setPadding(p, p, p, p);

        LinearLayout first = new LinearLayout(c);
        first.setGravity(Gravity.CENTER_VERTICAL);
        EditText desc = field(c, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, 14);
        desc.setHint("Produto");
        desc.setFilters(new InputFilter[] {new InputFilter.LengthFilter(140)});
        desc.setText(item.description);
        desc.setContentDescription("Item " + (index + 1));
        watch(desc, s -> item.description = s);
        first.addView(desc, new LayoutParams(0, Ui.dp(c, 34), 1));
        ImageView remove = new ImageView(c);
        remove.setImageResource(R.drawable.ic_x);
        remove.setImageTintList(ColorStateList.valueOf(Ui.color(c, R.color.muted_foreground)));
        int rp = Ui.dp(c, 8);
        remove.setPadding(rp, rp, rp, rp);
        remove.setBackgroundResource(android.R.drawable.list_selector_background);
        remove.setContentDescription("Remover item " + (index + 1));
        remove.setOnClickListener(v -> {
            items.remove(item);
            render();
            changed();
        });
        LayoutParams rlp = new LayoutParams(Ui.dp(c, 32), Ui.dp(c, 32));
        rlp.setMarginStart(Ui.dp(c, 4));
        first.addView(remove, rlp);
        row.addView(first);

        LinearLayout second = new LinearLayout(c);
        second.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        LayoutParams slp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.dp(c, 6);
        second.addView(label(c, "qtd"));
        EditText qty = field(c, InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL, 14);
        qty.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        qty.setText(item.quantity == null ? "" : quantityText(item.quantity));
        qty.setKeyListener(android.text.method.DigitsKeyListener.getInstance("0123456789,."));
        qty.setContentDescription("Quantidade do item " + (index + 1));
        watch(qty, s -> {
            String raw = s.replace(',', '.').trim();
            try {
                item.quantity = raw.isEmpty() ? null : Double.parseDouble(raw);
            } catch (NumberFormatException e) {
                item.quantity = null;
            }
        });
        LayoutParams qlp = new LayoutParams(Ui.dp(c, 56), Ui.dp(c, 30));
        qlp.setMarginStart(Ui.dp(c, 6));
        second.addView(qty, qlp);
        TextView rs = label(c, "R$");
        LayoutParams rslp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        rslp.setMarginStart(Ui.dp(c, 12));
        second.addView(rs, rslp);
        EditText total = field(c, InputType.TYPE_CLASS_NUMBER, 14);
        total.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        total.setText(Format.moneyPlain(item.total == null ? 0 : item.total));
        total.setContentDescription("Valor do item " + (index + 1));
        total.setOnFocusChangeListener((v, focus) -> {
            if (!focus) {
                total.setText(Format.moneyPlain(item.total == null ? 0 : item.total));
            }
        });
        watch(total, s -> {
            Long v = Format.parseMoneyInput(s);
            item.total = v == null ? 0L : v;
            refreshSum();
        });
        LayoutParams tlp = new LayoutParams(Ui.dp(c, 96), Ui.dp(c, 30));
        tlp.setMarginStart(Ui.dp(c, 6));
        second.addView(total, tlp);
        row.addView(second, slp);
        return row;
    }

    private static String quantityText(double q) {
        return q == Math.rint(q) ? String.valueOf((long) q) : String.valueOf(q).replace('.', ',');
    }

    private static TextView label(Context c, String text) {
        TextView tv = new TextView(c, null, 0, R.style.Text_Muted_Xs);
        tv.setText(text);
        return tv;
    }

    private static EditText field(Context c, int inputType, int sp) {
        EditText e = new EditText(c, null, 0, R.style.Input);
        e.setInputType(inputType);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        e.setMinHeight(0);
        e.setMinimumHeight(0);
        e.setSingleLine(true);
        int p = Ui.dp(c, 8);
        e.setPadding(p, 0, p, 0);
        e.setImportantForAutofill(IMPORTANT_FOR_AUTOFILL_NO);
        return e;
    }

    private static void watch(EditText e, Consumer<String> l) {
        e.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                l.accept(s.toString());
            }
        });
    }
}
