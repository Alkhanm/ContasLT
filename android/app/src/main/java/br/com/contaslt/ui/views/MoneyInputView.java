package br.com.contaslt.ui.views;

import java.util.function.Consumer;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import br.com.contaslt.R;
import br.com.contaslt.ui.Format;
import br.com.contaslt.ui.Ui;

/**
 * Campo de valor (MoneyInput.vue): "R$" pequeno e o valor grande em negrito; os dígitos digitados viram centavos
 * ("4250" → 42,50). {@link #value()} em centavos, null se vazio.
 */
public class MoneyInputView extends LinearLayout {

    private final EditText input;
    private boolean updating;
    private Long value;
    private Consumer<Long> listener;

    public MoneyInputView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBackgroundResource(R.drawable.bg_input_card);
        int p = Ui.dp(context, 16);
        setPadding(p, Ui.dp(context, 10), p, Ui.dp(context, 10));
        TextView prefix = new TextView(context, null, 0, R.style.Text_Semibold_Sm);
        prefix.setText("R$");
        prefix.setTextColor(Ui.color(context, R.color.muted_foreground));
        addView(prefix);
        input = new EditText(context, null, 0, R.style.Text_Huge);
        input.setBackground(null);
        input.setPadding(Ui.dp(context, 8), 0, 0, 0);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("0,00");
        input.setHintTextColor((Ui.color(context, R.color.muted_foreground) & 0x00FFFFFF) | 0x99000000);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        input.setSingleLine(true);
        addView(input, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (updating) {
                    return;
                }
                Long v = Format.parseMoneyInput(s.toString());
                setValueInternal(v);
                if (listener != null) {
                    listener.accept(v);
                }
            }
        });
    }

    public Long value() {
        return value;
    }

    public void setValue(Long cents) {
        setValueInternal(cents);
    }

    public void setOnChange(Consumer<Long> l) {
        listener = l;
    }

    public void setInvalid(boolean invalid) {
        setBackgroundResource(invalid ? R.drawable.bg_input_invalid : R.drawable.bg_input_card);
    }

    public EditText editText() {
        return input;
    }

    private void setValueInternal(Long v) {
        value = v;
        String text = v == null ? "" : Format.moneyPlain(v);
        updating = true;
        input.setText(text);
        input.setSelection(text.length());
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, text.length() > 12 ? 24 : 30);
        updating = false;
    }
}
