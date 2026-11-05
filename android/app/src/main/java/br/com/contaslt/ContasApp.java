package br.com.contaslt;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

/** Aplicação: cria o contêiner de dependências e aplica o tema escolhido (claro, escuro ou do sistema). */
public class ContasApp extends Application {

    private AppContainer container;

    @Override
    public void onCreate() {
        super.onCreate();
        container = new AppContainer(this);
        applyTheme(container.prefs.theme());
        // Comprovantes: o servidor lê a foto; o app aplica as leituras adiadas depois de cada sincronização.
        br.com.contaslt.services.ReceiptsService.setApi(container.api);
        br.com.contaslt.ui.TxActions.retryReceipt = br.com.contaslt.services.ReceiptsService::retryReceipt;
        container.session.onAfterSync(br.com.contaslt.services.ReceiptsService::processPendingReceipts);
    }

    public AppContainer container() {
        return container;
    }

    public static void applyTheme(String theme) {
        AppCompatDelegate.setDefaultNightMode("dark".equals(theme) ? AppCompatDelegate.MODE_NIGHT_YES
            : "light".equals(theme) ? AppCompatDelegate.MODE_NIGHT_NO : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }
}
