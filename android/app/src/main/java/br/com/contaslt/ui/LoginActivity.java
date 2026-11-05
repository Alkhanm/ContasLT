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

        b.mode.setOptions(new String[] {"Entrar", "Criar conta"}, new String[] {"login", "register"});
        b.mode.setOnChange(m -> {
            register = "register".equals(m);
            Ui.visible(b.nameGroup, register);
            Ui.visible(b.passwordHint, register);
            b.submit.setText(register ? "Criar minha conta" : "Entrar");
            clearErrors();
        });
        b.togglePassword.setOnClickListener(v -> {
            showPassword = !showPassword;
            b.password.setTransformationMethod(showPassword ? HideReturnsTransformationMethod.getInstance()
                : PasswordTransformationMethod.getInstance());
            b.togglePassword.setImageResource(showPassword ? R.drawable.ic_eye_off : R.drawable.ic_eye);
            b.togglePassword.setContentDescription(showPassword ? "Ocultar senha" : "Mostrar senha");
            b.password.setSelection(b.password.getText().length());
        });
        b.server.setText(app.prefs.server());
        b.serverToggle.setOnClickListener(v -> Ui.visible(b.server, b.server.getVisibility() != android.view.View.VISIBLE));
        b.submit.setOnClickListener(v -> submit());
    }

    private void submit() {
        clearErrors();
        String name = b.name.getText().toString().trim();
        String email = b.email.getText().toString().trim();
        String password = b.password.getText().toString();
        String server = b.server.getText().toString().trim();
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
