package br.com.contaslt.ui;

import java.util.Map;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import br.com.contaslt.AppContainer;
import br.com.contaslt.ContasApp;
import br.com.contaslt.R;
import br.com.contaslt.databinding.ActivityForgotPasswordBinding;
import br.com.contaslt.ui.views.UiKit;

/** "Esqueci minha senha" (mesmo fluxo do PWA): pede o e-mail, depois o código de 6 dígitos e a nova senha, e entra. */
public class ForgotPasswordActivity extends AppCompatActivity {

    public static final String EXTRA_EMAIL = "email";
    public static final String EXTRA_SERVER = "server";

    private ActivityForgotPasswordBinding b;
    private AppContainer app;
    private String server;
    private String email;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = ((ContasApp) getApplication()).container();
        b = ActivityForgotPasswordBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(b.getRoot(), (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()
                | androidx.core.view.WindowInsetsCompat.Type.ime());
            v.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });
        server = getIntent().getStringExtra(EXTRA_SERVER);
        if (server == null) {
            server = app.prefs.server();
        }
        String initial = getIntent().getStringExtra(EXTRA_EMAIL);
        if (initial != null) {
            b.email.setText(initial);
        }
        // Campo de senha usa fonte monoespaçada por padrão; mantém a Manrope do app.
        b.password.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, R.font.manrope));
        b.back.setOnClickListener(v -> finish());
        b.send.setOnClickListener(v -> send(b.send));
        b.resend.setOnClickListener(v -> send(b.resend));
        b.save.setOnClickListener(v -> save());
    }

    private void send(TextView button) {
        clearErrors();
        String typed = b.email.getText().toString().trim();
        if (typed.isEmpty()) {
            showError(b.emailError, "Informe o e-mail");
            return;
        }
        button.setEnabled(false);
        Repo.run(this, () -> {
            app.auth.forgotPassword(server, typed);
            return true;
        }, ok -> {
            button.setEnabled(true);
            email = typed;
            b.intro.setText("Se existir uma conta com " + typed + ", o código chega em instantes. Confira também o spam. "
                + "Ele vale por 15 minutos.");
            Ui.visible(b.emailStep, false);
            Ui.visible(b.codeStep, true);
            b.code.setText("");
            b.code.requestFocus();
            if (button == b.resend) {
                UiKit.toast(this, "Código enviado de novo");
            }
        }, e -> {
            button.setEnabled(true);
            Map<String, String> errors = e.errors;
            showError(b.emailError, errors.get("email"));
            if (errors.isEmpty()) {
                showError(b.formError, e.getMessage());
            }
        });
    }

    private void save() {
        clearErrors();
        String code = b.code.getText().toString().trim();
        String password = b.password.getText().toString();
        if (code.length() != 6) {
            showError(b.codeError, "O código tem 6 dígitos");
            return;
        }
        b.save.setEnabled(false);
        b.save.setText("Aguarde…");
        Repo.run(this, () -> {
            app.auth.resetPassword(server, email, code, password);
            return true;
        }, ok -> {
            UiKit.toast(this, "Senha alterada");
            Intent main = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(main);
            finish();
        }, e -> {
            b.save.setEnabled(true);
            b.save.setText("Salvar nova senha");
            Map<String, String> errors = e.errors;
            showError(b.codeError, errors.get("code"));
            showError(b.passwordError, errors.get("password"));
            if (errors.isEmpty()) {
                showError(b.formError, e.getMessage());
            }
        });
    }

    private void clearErrors() {
        for (TextView tv : new TextView[] {b.emailError, b.codeError, b.passwordError, b.formError}) {
            Ui.visible(tv, false);
        }
    }

    private static void showError(TextView tv, String msg) {
        tv.setText(msg);
        Ui.visible(tv, msg != null && !msg.isEmpty());
    }
}
