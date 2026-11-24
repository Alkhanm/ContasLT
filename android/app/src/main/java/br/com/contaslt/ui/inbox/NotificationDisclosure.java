package br.com.contaslt.ui.inbox;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import br.com.contaslt.notify.InboxStore;

/**
 * Aviso destacado antes de pedir o acesso às notificações (política de dados do usuário da Play Store): diz o que é
 * lido, para quê e o que vai para a conta, e só abre as configurações do sistema se o usuário aceitar. Aceitar
 * também liga a sincronização da caixa A confirmar ({@link InboxStore#syncConsent()}).
 */
public final class NotificationDisclosure {

    static final String SYNC_TEXT =
        "• As sugestões (com o texto da notificação reconhecida) são guardadas na sua conta, para você confirmar "
            + "também no site, em outro aparelho. Não são compartilhadas com terceiros e somem 30 dias depois de "
            + "confirmadas ou descartadas. Notificações não reconhecidas ficam só neste aparelho.";

    static final String TEXT =
        "Para lançar suas compras, Pix e pagamentos automaticamente, o ContasLT precisa ler as notificações que os "
            + "apps dos bancos mostram no seu celular.\n\n"
            + "• Só são lidas as notificações dos apps de banco que você marcar na tela Notificações dos bancos; as "
            + "dos outros apps são ignoradas.\n"
            + "• Do texto da notificação o app tira valor, data, estabelecimento ou pessoa e final do cartão, e cria "
            + "uma sugestão na caixa A confirmar. Nada vira lançamento sem você confirmar.\n"
            + SYNC_TEXT + "\n"
            + "• Você pode desligar o acesso a qualquer momento nas configurações do Android.";

    private NotificationDisclosure() {
    }

    /** Mostra o aviso; em "Aceito e continuar", liga a sincronização da caixa e abre as configurações de acesso. */
    public static void show(Context c, InboxStore store) {
        new MaterialAlertDialogBuilder(c)
            .setTitle("Ler notificações dos bancos")
            .setMessage(TEXT)
            .setNegativeButton("Agora não", null)
            .setPositiveButton("Aceito e continuar", (d, w) -> {
                if (store != null) {
                    store.setSyncConsent(true);
                }
                c.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            })
            .show();
    }

    /**
     * Quem liberou o acesso numa versão anterior (sugestões só no aparelho): pergunta uma vez se a caixa pode ir para
     * a conta. Recusar mantém tudo no aparelho; dá para mudar depois em Notificações dos bancos.
     */
    public static void askSyncOnce(Context c, InboxStore store, Runnable after) {
        if (store == null || store.syncConsent() || store.syncAsked()) {
            return;
        }
        store.setSyncAsked();
        new MaterialAlertDialogBuilder(c)
            .setTitle("Confirmar também no site")
            .setMessage("Agora a caixa A confirmar pode aparecer no site do ContasLT e em outros aparelhos: o que você "
                + "confirmar ou descartar em um some do outro.\n\n" + SYNC_TEXT
                + "\n\nSe preferir manter tudo só neste celular, toque em Agora não (dá para mudar em Notificações dos bancos).")
            .setNegativeButton("Agora não", null)
            .setPositiveButton("Ativar", (d, w) -> {
                store.setSyncConsent(true);
                if (after != null) {
                    after.run();
                }
            })
            .show();
    }
}
