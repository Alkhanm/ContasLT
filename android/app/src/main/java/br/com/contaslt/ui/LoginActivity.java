package br.com.contaslt.ui;

import java.util.Map;

import android.content.Intent;
import android.os.Bundle;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import br.com.contaslt.AppContainer;
import br.com.contaslt.ContasApp;
import br.com.contaslt.R;
import br.com.contaslt.databinding.ActivityLoginBinding;

/** Entrar ou criar conta (LoginPage.vue). O campo "Servidor" fica recolhido: só para apontar a outro backend. */
public class LoginActivity extends AppCompatActivity {

    private ActivityLoginBinding b;
    private AppContainer app;
    private boolean register;
    private boolean showPassword;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = ((ContasApp) getApplication()).container();
        b = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        // O topo escuro vai até atrás da barra de status; o conteúdo dele desce o tamanho dela.
        int heroTop = b.hero.getPaddingTop();
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(b.getRoot(), (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            b.hero.setPadding(b.hero.getPaddingLeft(), heroTop + bars.top, b.hero.getPaddingRight(), b.hero.getPaddingBottom());
            v.setPadding(0, 0, 0, bars.bottom);
            return insets;
        });

        // Campo de senha usa fonte monoespaçada por padrão; mantém a Manrope do app.
        b.password.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, R.font.manrope));
        b.mode.setOptions(new String[] {"Entrar", "Criar conta"}, new String[] {"login", "register"});
        b.mode.setOnChange(m -> {
            register = "register".equals(m);
            Ui.visible(b.nameGroup, register);
            Ui.visible(b.passwordHint, register);
            Ui.visible(b.forgot, !register);
            b.submit.setText(register ? "Criar minha conta" : "Entrar");
            clearErrors();
        });
        b.togglePassword.setOnClickListener(v -> {
            showPassword = !showPassword;
            b.password.setTransformationMethod(showPassword ? HideReturnsTransformationMethod.getInstance()
                : PasswordTransformationMethod.getInstance());
            b.togglePassword.setImageResource(showPassword ? R.drawable.ic_eye_off : R.drawable.ic_eye);
            b.togglePassword.setContentDescription(showPassword ? "Ocultar senha" : "Mostrar senha");
            b.password.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, R.font.manrope));
            b.password.setSelection(b.password.getText().length());
        });
        b.server.setText(app.prefs.server());
        // Trocar de servidor é coisa de desenvolvimento: no app da loja o campo não aparece.
        Ui.visible(b.serverToggle, br.com.contaslt.BuildConfig.SHOW_SERVER_FIELD);
        b.serverToggle.setOnClickListener(v -> Ui.visible(b.server, b.server.getVisibility() != android.view.View.VISIBLE));
        b.submit.setOnClickListener(v -> submit());
        b.forgot.setOnClickListener(v -> {
            String server = server();
            if (server != null) {
                startActivity(new Intent(this, ForgotPasswordActivity.class)
                    .putExtra(ForgotPasswordActivity.EXTRA_EMAIL, b.email.getText().toString().trim())
                    .putExtra(ForgotPasswordActivity.EXTRA_SERVER, server));
            }
        });
        Ui.visible(b.googleGroup, GoogleSignIn.available());
        b.google.setOnClickListener(v -> google());
    }

    /** Endereço do servidor do campo (recolhido na loja), ou nulo com o erro já na tela. */
    private String server() {
        String server = b.server.getText().toString().trim().replaceAll("/+$", "");
        if (!server.matches("https?://[\\w.-]+(:\\d+)?(/.*)?")) {
            showError(b.formError, "Servidor inválido. Use o endereço completo, ex.: https://contaslt-production.up.railway.app");
            return null;
        }
        return server;
    }

    private void google() {
        clearErrors();
        String server = server();
        if (server == null) {
            return;
        }
        GoogleSignIn.requestIdToken(this, token -> {
            b.google.setEnabled(false);
            b.google.setText("Aguarde…");
            Repo.run(this, () -> {
                app.auth.loginWithGoogle(server, token);
                return true;
            }, ok -> {
                startActivity(new Intent(this, MainActivity.class));
                finish();
            }, e -> {
                b.google.setEnabled(true);
                b.google.setText("Continuar com o Google");
                showError(b.formError, e.getMessage());
            });
        }, msg -> showError(b.formError, msg));
    }

    private void submit() {
        clearErrors();
        String name = b.name.getText().toString().trim();
        String email = b.email.getText().toString().trim();
        String password = b.password.getText().toString();
        String server = server();
        if (server == null) {
            return;
        }
        b.submit.setEnabled(false);
        b.submit.setText("Aguarde…");
        Repo.run(this, () -> {
            if (register) {
                app.auth.register(server, name, email, password);
            } else {
                app.auth.login(server, email, password);
            }
            return true;
        }, ok -> {
            startActivity(new Intent(this, MainActivity.class));
            finish();
        }, e -> {
            b.submit.setEnabled(true);
            b.submit.setText(register ? "Criar minha conta" : "Entrar");
            Map<String, String> errors = e.errors;
            showError(b.nameError, errors.get("name"));
            showError(b.emailError, errors.get("email"));
            showError(b.passwordError, errors.get("password"));
            if (errors.isEmpty()) {
                showError(b.formError, e.getMessage());
            }
        });
    }

    private void clearErrors() {
        for (TextView tv : new TextView[] {b.nameError, b.emailError, b.passwordError, b.formError}) {
            Ui.visible(tv, false);
        }
    }

    private static void showError(TextView tv, String msg) {
        tv.setText(msg);
        Ui.visible(tv, msg != null && !msg.isEmpty());
    }
}
