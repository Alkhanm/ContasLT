package br.com.contaslt.notify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

/** Junção dos campos de texto da notificação e atualização da mesma notificação pelo banco. */
public class NotificationTextTest {

    @Test
    public void juntaCamposSemRepetir() {
        // Texto curto truncado é o começo do expandido; subtexto traz a conta.
        String t = NotificationText.join("Pix enviado", Arrays.asList(
            "Você enviou R$ 50,00 para JOAO DA SILVA, chave joao@email.com",
            "Você enviou R$ 50,00 para JOAO…",
            "Conta corrente final 9876",
            "Pix enviado"));
        assertEquals("Você enviou R$ 50,00 para JOAO DA SILVA, chave joao@email.com\nConta corrente final 9876", t);
    }

    @Test
    public void textoMaiorSubstituiOMenor() {
        assertEquals("R$ 50,00 para Maria Souza", NotificationText.join(null, Arrays.asList("R$ 50,00", "R$ 50,00 para Maria Souza")));
    }

    @Test
    public void nadaAlemDoTitulo() {
        assertNull(NotificationText.join("Pix enviado", Arrays.asList("Pix enviado", " ", null)));
    }

    private static InboxItem item(String status, String title, String text, String type, long amount) {
        InboxItem i = new InboxItem();
        i.status = status;
        i.title = title;
        i.text = text;
        i.type = type;
        i.amount = amount;
        return i;
    }

    @Test
    public void mesmoMovimentoReescrito() {
        InboxItem a = item(InboxItem.PENDING, "Pix enviado", "R$ 50,00", "EXPENSE", 5000);
        InboxItem b = item(InboxItem.PENDING, "Pix enviado", "R$ 50,00 para Maria", "EXPENSE", 5000);
        assertTrue(NotificationProcessor.sameMovement(a, b));
        // Mesma chave, outro valor: é outra movimentação (app que usa uma chave só).
        assertFalse(NotificationProcessor.sameMovement(a, item(InboxItem.PENDING, "Pix enviado", "R$ 20,00", "EXPENSE", 2000)));
        // Não reconhecida que ganhou o resto do texto.
        InboxItem u = item(InboxItem.UNRECOGNIZED, "Pix", "Transação realizada", null, 0);
        assertTrue(NotificationProcessor.sameMovement(u, item(InboxItem.PENDING, "Pix", "Transação realizada: R$ 50,00 enviados", "EXPENSE", 5000)));
        assertFalse(NotificationProcessor.sameMovement(u, item(InboxItem.PENDING, "Compra", "R$ 9,00 em Padaria", "EXPENSE", 900)));
    }

    @Test
    public void observacaoTrazHorarioETextoOriginal() {
        InboxItem i = item(InboxItem.PENDING, "Pix enviado", "R$ 50,00 para Maria", "EXPENSE", 5000);
        i.appLabel = "Nubank";
        i.postedAt = java.time.LocalDateTime.of(2026, 11, 5, 14, 32).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        assertEquals("Via notificação · Nubank · 05/11 14:32\nPix enviado\nR$ 50,00 para Maria", InboxService.notes(i));
    }
}
