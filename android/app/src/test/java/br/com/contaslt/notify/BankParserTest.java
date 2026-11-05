package br.com.contaslt.notify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Textos no formato das notificações dos bancos brasileiros. */
public class BankParserTest {

    private static final String TODAY = "2026-11-05";

    private static BankParser.Parsed p(String title, String text) {
        return BankParser.parse(title, text, TODAY);
    }

    @Test
    public void compraNoCartaoNubank() {
        BankParser.Parsed r = p("Compra aprovada", "Compra de R$ 45,90 APROVADA em PADARIA PAO QUENTE para o cartão com final 1234.");
        assertEquals("EXPENSE", r.type);
        assertEquals(4590, r.amount);
        assertEquals(BankParser.Kind.CARD, r.kind);
        assertEquals("PADARIA PAO QUENTE", r.counterpart);
        assertEquals("Padaria Pao Quente", r.description());
        assertEquals("1234", r.cardLast4);
        assertEquals(TODAY, r.date);
    }

    @Test
    public void compraItauComDataEHora() {
        BankParser.Parsed r = p("Itaú", "Compra aprovada no cartão final 9876: R$ 1.234,56 em MAGAZINE LOJA SA em 04/11 às 10:21.");
        assertEquals("EXPENSE", r.type);
        assertEquals(123456, r.amount);
        assertEquals("MAGAZINE LOJA SA", r.counterpart);
        assertEquals("9876", r.cardLast4);
        assertEquals("2026-11-04", r.date);
    }

    @Test
    public void pixRecebido() {
        BankParser.Parsed r = p("Pix recebido", "Você recebeu um Pix de R$ 200,00 de Maria da Silva.");
        assertEquals("INCOME", r.type);
        assertEquals(20000, r.amount);
        assertEquals(BankParser.Kind.PIX, r.kind);
        assertEquals("Maria da Silva", r.counterpart);
    }

    @Test
    public void pixEnviado() {
        BankParser.Parsed r = p("Pix enviado", "Pix de R$ 50,00 enviado para João Souza");
        assertEquals("EXPENSE", r.type);
        assertEquals(5000, r.amount);
        assertEquals("João Souza", r.counterpart);
    }

    @Test
    public void transferenciaRecebidaNubank() {
        BankParser.Parsed r = p(null, "Você recebeu uma transferência de R$ 1.500,00 de EMPRESA LTDA.");
        assertEquals("INCOME", r.type);
        assertEquals(150000, r.amount);
        assertEquals(BankParser.Kind.TRANSFER, r.kind);
        assertEquals("Empresa Ltda", r.description());
    }

    @Test
    public void mercadoPagoPagou() {
        BankParser.Parsed r = p("Mercado Pago", "Você pagou R$ 32,50 a Restaurante Bom Sabor.");
        assertEquals("EXPENSE", r.type);
        assertEquals(3250, r.amount);
        assertEquals("Restaurante Bom Sabor", r.counterpart);
    }

    @Test
    public void boletoPago() {
        BankParser.Parsed r = p("Pagamento realizado", "Pagamento de boleto de R$ 231,40 realizado com sucesso.");
        assertEquals("EXPENSE", r.type);
        assertEquals(BankParser.Kind.BOLETO, r.kind);
        assertEquals("Pagamento de boleto", r.description());
    }

    @Test
    public void estornoViraReceita() {
        BankParser.Parsed r = p("Estorno", "Estorno de R$ 19,90 da compra em LOJA X no cartão final 1234.");
        assertEquals("INCOME", r.type);
        assertEquals(1990, r.amount);
    }

    @Test
    public void ignoraCodigosFaturaECompraNegada() {
        assertNull(p("Nubank", "Seu código de verificação é 123456. Não compartilhe."));
        assertNull(p("Fatura fechada", "Sua fatura fechou em R$ 2.345,67 e vence em 10/11."));
        assertNull(p("Compra recusada", "Compra de R$ 99,00 em LOJA foi recusada."));
        assertNull(p("Novidade", "Conheça nossa nova conta digital!"));
        assertNull(p("Lembrete", "Lembrete: boleto de R$ 100,00 vence amanhã."));
    }

    @Test
    public void dataDistanteFicaADoAviso() {
        BankParser.Parsed r = p("Compra aprovada", "Compra de R$ 10,00 em LOJA válida até 31/12/2030");
        assertEquals(TODAY, r.date);
    }

    @Test
    public void centavos() {
        assertEquals(123456, BankParser.cents("1.234,56"));
        assertEquals(1050, BankParser.cents("10,5"));
        assertEquals(700, BankParser.cents("7"));
    }
}
