package br.com.contaslt.ui.inbox;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Aviso destacado antes de pedir o acesso às notificações (política de dados do usuário da Play Store): diz o que é
 * lido, para quê e que nada sai do aparelho, e só abre as configurações do sistema se o usuário aceitar.
 */
public final class NotificationDisclosure {

    static final String TEXT =
        "Para lançar suas compras, Pix e pagamentos automaticamente, o ContasLT precisa ler as notificações que os "
            + "apps dos bancos mostram no seu celular.\n\n"
            + "• Só são lidas as notificações dos apps de banco que você marcar na tela Notificações dos bancos; as "
            + "dos outros apps são ignoradas.\n"
            + "• Do texto da notificação o app tira valor, data, estabelecimento e final do cartão, e cria uma sugestão "
            + "na caixa A confirmar. Nada vira lançamento sem você confirmar.\n"
            + "• O conteúdo das notificações fica só neste aparelho: não é enviado ao servidor nem a terceiros. Só os "
            + "lançamentos que você confirmar são sincronizados com a sua conta.\n"
            + "• Você pode desligar o acesso a qualquer momento nas configurações do Android.";

    private NotificationDisclosure() {
    }

    /** Mostra o aviso; em "Aceito e continuar", abre as configurações de acesso a notificações. */
    public static void show(Context c) {
        new MaterialAlertDialogBuilder(c)
            .setTitle("Ler notificações dos bancos")
            .setMessage(TEXT)
            .setNegativeButton("Agora não", null)
            .setPositiveButton("Aceito e continuar", (d, w) ->
                c.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)))
            .show();
    }
}
