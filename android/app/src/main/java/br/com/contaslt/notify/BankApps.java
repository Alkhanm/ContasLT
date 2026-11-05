package br.com.contaslt.notify;

import java.util.Arrays;
import java.util.List;

/** Apps de banco conhecidos (pré-marcados quando instalados). Outros podem ser adicionados na tela de notificações. */
public final class BankApps {

    public record App(String packageName, String label) {
    }

    public static final List<App> KNOWN = Arrays.asList(
        new App("com.nu.production", "Nubank"),
        new App("com.itau", "Itaú"),
        new App("com.itau.pers", "Itaú Personnalité"),
        new App("br.com.intermedium", "Inter"),
        new App("com.c6bank.app", "C6 Bank"),
        new App("com.bradesco", "Bradesco"),
        new App("br.com.bradesco.next", "next"),
        new App("com.santander.app", "Santander"),
        new App("br.com.bb.android", "Banco do Brasil"),
        new App("br.com.gabba.Caixa", "CAIXA"),
        new App("com.picpay", "PicPay"),
        new App("com.mercadopago.wallet", "Mercado Pago"),
        new App("br.com.uol.ps.myaccount", "PagBank"),
        new App("br.com.neon", "Neon"),
        new App("com.btg.pactual.banking", "BTG Pactual"),
        new App("br.com.original.bank", "Original"),
        new App("com.sicredi.app", "Sicredi"),
        new App("br.com.sicoobnet", "Sicoob"));

    private BankApps() {
    }

    public static String label(String pkg) {
        for (App a : KNOWN) {
            if (a.packageName().equals(pkg)) {
                return a.label();
            }
        }
        return null;
    }
}
