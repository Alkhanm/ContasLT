package br.com.contaslt.services;

import static br.com.contaslt.services.Testing.account;
import static br.com.contaslt.services.Testing.bill;
import static br.com.contaslt.services.Testing.card;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import br.com.contaslt.data.ApiException;
import br.com.contaslt.data.schema.Entity;
import br.com.contaslt.domain.Dates;
import br.com.contaslt.dto.Account;
import br.com.contaslt.dto.Dashboard;
import br.com.contaslt.dto.DayBalance;
import br.com.contaslt.dto.Transaction;
import br.com.contaslt.dto.TransactionFilter;
import br.com.contaslt.dto.TransactionInput;
import br.com.contaslt.dto.TransactionPage;
import br.com.contaslt.dto.Transfer;
import br.com.contaslt.dto.TransferInput;
import br.com.contaslt.dto.TransferSuggestion;
import br.com.contaslt.dto.TransferSuggestionList;

/** Porte de frontend/src/services/transfers.test.ts. */
public class TransfersServiceTest {

    @Before
    public void setUp() {
        Testing.freshStore();
        // Só o relógio de datas: o cruzamento olha 18 meses para trás a partir de hoje.
        ZoneId zone = ZoneId.systemDefault();
        Dates.setClockForTests(Clock.fixed(LocalDateTime.of(2027, 4, 15, 12, 0).atZone(zone).toInstant(), zone));
    }

    @After
    public void tearDown() {
        Dates.resetForTests();
    }

    /** O erro lançado contém a mensagem (como o toThrow('...') do Vitest). */
    static ApiException assertThrowsMessage(String message, Runnable r) {
        ApiException e = assertThrows(ApiException.class, r::run);
        assertTrue(e.getMessage(), e.getMessage().contains(message));
        return e;
    }

    private static Transaction tx(String description, double reais, String type, String dueDate, String accountId,
                                  String categoryId) {
        TransactionInput in = Testing.tx(description, reais, type, dueDate, true, accountId, categoryId);
        return TransactionsService.createTransaction(in).transaction;
    }

    private static Transaction tx(String description, double reais, String type, String dueDate, String accountId) {
        return tx(description, reais, type, dueDate, accountId, null);
    }

    private static TransferInput transfer(String from, String to, double reais, String date, String description,
                                          boolean paid) {
        TransferInput in = new TransferInput();
        in.fromAccountId = from;
        in.toAccountId = to;
        in.amount = Testing.cents(reais);
        in.date = date;
        in.description = description;
        in.paid = paid;
        return in;
    }

    private static TransactionPage list(String month, String accountId, Boolean transfers) {
        TransactionFilter f = new TransactionFilter();
        f.month = month;
        f.accountId = accountId;
        f.transfers = transfers;
        return TransactionsService.listTransactions(f);
    }

    private static TransactionPage list(String month) {
        return list(month, null, null);
    }

    @Test
    public void entreContasSaoNeutrasCriacaoCruzamentoVinculoDesvinculoEExclusao() {
        Testing.Catalog c = Testing.basicCatalog();
        Account pj = CatalogService.createAccount(account("Conta PJ", "CHECKING", 0, "#123456"));
        String month = "2027-03";
        RecurringService.createRecurring(bill("Aluguel", 1600, "EXPENSE", "MONTHLY", "2027-01-10", null, c.account.id,
            c.expense.id));
        tx("Cliente", 1000, "INCOME", month + "-01", pj.id, c.income.id);

        // 1) Transferência criada diretamente: duas pernas vinculadas.
        Transfer tr = TransfersService.createTransfer(transfer(pj.id, c.account.id, 600, month + "-05", "Pró-labore", true));
        assertEquals("EXPENSE", tr.from.type);
        assertEquals("INCOME", tr.to.type);
        assertEquals(pj.id, tr.to.transferPeerAccountId);
        assertEquals(tr.id, tr.from.transferId);
        assertEquals(tr.id, tr.to.transferId);
        assertEquals("Pró-labore", tr.from.description);

        TransactionPage page = list(month);
        assertEquals(4, page.items.size());
        assertEquals(100000, page.summary.income);
        assertEquals(160000, page.summary.pendingExpense);
        assertEquals(0, page.summary.transfer); // 600 saiu, 600 entrou
        // O saldo de cada conta considera as pernas: a PJ recebeu 1000 e transferiu 600.
        assertEquals(40000, CatalogService.accountById(pj.id).balance);
        DayBalance pjDay = null;
        for (DayBalance d : list(month, pj.id, null).dayBalances) {
            if (d.date.equals(month + "-05")) {
                pjDay = d;
            }
        }
        assertEquals(40000, pjDay.balance);
        // Filtro "só transferências".
        assertEquals(2, list(month, null, true).items.size());

        // 2) Cruzamento automático: saída numa conta e entrada de mesmo valor na outra, 1 dia depois.
        Transaction out = tx("Transferência enviada pelo Pix", 250, "EXPENSE", month + "-10", pj.id);
        Transaction into = tx("Transferência recebida", 250, "INCOME", month + "-11", c.account.id);
        // Ruído: receita de mesmo valor na MESMA conta da saída não pode ser sugerida.
        tx("Reembolso", 250, "INCOME", month + "-10", pj.id);

        List<TransferSuggestion> suggestions = TransfersService.transferSuggestions(null).items;
        assertEquals(1, suggestions.size());
        assertEquals(out.id, suggestions.get(0).from.id);
        assertEquals(into.id, suggestions.get(0).to.id);
        assertEquals(1, suggestions.get(0).daysApart);

        Transfer linked = TransfersService.linkTransfer(into.id, out.id);
        assertEquals(out.id, linked.from.id);
        assertEquals(out.id, linked.to.transferPeerTransactionId);

        Dashboard dash = DashboardService.buildDashboard(month);
        assertEquals(125000, dash.income);
        assertEquals(160000, dash.expense);
        assertEquals(85000, dash.transfers);
        assertEquals(0, TransfersService.transferSuggestions(null).items.size());

        // Vincular de novo é recusado; valores diferentes também.
        assertThrowsMessage("Um dos lançamentos já faz parte de outra transferência",
            () -> TransfersService.linkTransfer(into.id, out.id));
        Transaction other = tx("Outro", 99, "INCOME", month + "-12", c.account.id);
        Transaction reembolso = null;
        for (Transaction t : list(month).items) {
            if (t.description.equals("Reembolso")) {
                reembolso = t;
            }
        }
        String reembolsoId = reembolso.id;
        assertThrowsMessage("Um dos lançamentos já faz parte de outra transferência",
            () -> TransfersService.linkTransfer(other.id, tr.from.id));
        assertThrows(ApiException.class, () -> TransfersService.linkTransfer(other.id, out.id));
        assertThrowsMessage("Escolha dois lançamentos diferentes",
            () -> TransfersService.linkTransfer(reembolsoId, reembolsoId));

        // 3) Desvincular devolve os dois lançamentos ao resultado.
        TransfersService.unlinkTransfer(linked.id);
        assertNull(Ctx.db().get(Entity.TRANSFER, linked.id));
        assertNull(TransactionsService.getTransaction(out.id).transferId);
        assertNull(TransactionsService.getTransaction(into.id).transferId);
        dash = DashboardService.buildDashboard(month);
        assertEquals(159900, dash.income); // 1000 + 250 + 250 + 99
        assertEquals(185000, dash.expense);
        assertThrowsMessage("Transferência não encontrada", () -> TransfersService.unlinkTransfer(linked.id));

        // 4) Excluir uma perna desfaz o vínculo e deixa a outra solta.
        TransactionsService.deleteTransaction(tr.from.id);
        assertNull(TransactionsService.getTransaction(tr.to.id).transferId);
    }

    @Test
    public void regrasDaCriacaoEDoVinculo() {
        Testing.Catalog cat = Testing.basicCatalog();
        String acc = cat.account.id;
        Account cardAcc = CatalogService.createAccount(card("Cartão", 0, "#000000", 5, 12));
        Account savings = CatalogService.createAccount(account("Poupança", "SAVINGS", 0, "#000000"));

        assertThrowsMessage("Origem e destino precisam ser contas diferentes",
            () -> TransfersService.createTransfer(transfer(acc, acc, 10, "2027-04-01", null, true)));
        assertThrowsMessage("Cartão de crédito não entra em transferência",
            () -> TransfersService.createTransfer(transfer(acc, cardAcc.id, 10, "2027-04-01", null, true)));
        assertThrowsMessage("Dados inválidos",
            () -> TransfersService.createTransfer(transfer(acc, savings.id, 0, "2027-04-01", null, true)));

        // Descrição em branco vira "Transferência". (O PWA também cria paga quando `paid` falta; aqui o DTO tem
        // boolean primitivo, então quem chama sempre informa.)
        Transfer t = TransfersService.createTransfer(transfer(acc, savings.id, 10, "2027-04-01", "  ", true));
        assertEquals("Transferência", t.from.description);
        assertTrue(t.from.paid);
        assertEquals(savings.id, t.to.accountId);

        // O vínculo tira a categoria das duas pernas.
        Transaction a = tx("Saída", 30, "EXPENSE", "2027-04-02", acc, cat.expense.id);
        Transaction b = tx("Entrada", 30, "INCOME", "2027-04-02", acc);
        Transaction c = tx("Entrada", 31, "INCOME", "2027-04-02", savings.id);
        Transaction d = tx("Saída 2", 30, "EXPENSE", "2027-04-02", savings.id);
        Transaction purchase = tx("Compra", 30, "EXPENSE", "2027-04-02", cardAcc.id);
        assertThrowsMessage("Uma transferência precisa de uma saída (despesa) e uma entrada (receita)",
            () -> TransfersService.linkTransfer(a.id, d.id));
        assertThrowsMessage("A saída e a entrada precisam estar em contas diferentes",
            () -> TransfersService.linkTransfer(a.id, b.id));
        assertThrowsMessage("Os dois lançamentos precisam ter o mesmo valor",
            () -> TransfersService.linkTransfer(a.id, c.id));
        assertThrowsMessage("Compras de cartão não entram em transferência",
            () -> TransfersService.linkTransfer(purchase.id, b.id));
        Transfer linked = TransfersService.linkTransfer(b.id, d.id);
        assertEquals(d.id, linked.from.id);
        Transaction withCategory = tx("Saída 3", 31, "EXPENSE", "2027-04-02", acc, cat.expense.id);
        Transfer l2 = TransfersService.linkTransfer(withCategory.id, c.id);
        assertNull(l2.from.categoryId);
        assertNull(Ctx.db().get(Entity.TRANSACTION, withCategory.id).categoryId);
    }

    @Test
    public void recusarUmParDeRecorrentesSilenciaTodosOsMeses() {
        Testing.Catalog cat = Testing.basicCatalog();
        Account invest = CatalogService.createAccount(account("Corretora", "INVESTMENT", 0, "#123456"));
        // Saída recorrente de 500 na conta principal e entrada recorrente de 500 na corretora, dia 1, 4 parcelas.
        RecurringService.createRecurring(bill("Bitcoin", 500, "EXPENSE", "MONTHLY", "2026-01-01", 4, cat.account.id, null));
        RecurringService.createRecurring(bill("Aporte", 500, "INCOME", "MONTHLY", "2026-01-01", 4, invest.id, null));
        // As ocorrências nascem quando o mês é consultado.
        for (String month : new String[] {"2026-01", "2026-02", "2026-03", "2026-04"}) {
            list(month);
        }

        TransferSuggestionList before = TransfersService.transferSuggestions(null);
        assertEquals(4, before.items.size());
        assertEquals(0, before.rejected);

        TransferSuggestion pair = before.items.get(0);
        assertTrue(TransfersService.rejectTransfer(pair.from.id, pair.to.id));
        // Recusar de novo não duplica.
        TransfersService.rejectTransfer(pair.to.id, pair.from.id);

        TransferSuggestionList after = TransfersService.transferSuggestions(null);
        assertEquals(0, after.items.size()); // os outros 3 meses sumiram junto
        assertEquals(1, after.rejected);

        // Um lançamento avulso de 500 na corretora ainda cruza com o "Bitcoin" (a recusa foi só entre as duas regras).
        Transaction loose = tx("Pix recebido", 500, "INCOME", "2026-02-02", invest.id);
        TransferSuggestionList looseList = TransfersService.transferSuggestions(null);
        assertEquals(1, looseList.items.size());
        assertEquals("Pix recebido", looseList.items.get(0).to.description);

        // Recusa de lançamento avulso: só aquele par.
        assertFalse(TransfersService.rejectTransfer(looseList.items.get(0).from.id, loose.id));
        assertEquals(0, TransfersService.transferSuggestions(null).items.size());
        assertThrowsMessage("Escolha dois lançamentos diferentes", () -> TransfersService.rejectTransfer(loose.id, loose.id));

        TransfersService.forgetRejections();
        // Volta a cruzar: o avulso de fevereiro e o Aporte disputam o Bitcoin de fevereiro (empate: fica a entrada mais
        // antiga, o Aporte).
        TransferSuggestionList again = TransfersService.transferSuggestions(null);
        assertEquals(0, again.rejected);
        assertEquals(4, again.items.size());
        List<String> dates = new ArrayList<>();
        for (TransferSuggestion s : again.items) {
            dates.add(s.from.dueDate);
        }
        assertEquals(Arrays.asList("2026-04-01", "2026-03-01", "2026-02-01", "2026-01-01"), dates);
        assertEquals("Aporte", again.items.get(2).to.description);
    }

    @Test
    public void pontuaDescricoesQueCitamAOutraContaOuOProprioUsuario() {
        Testing.Catalog cat = Testing.basicCatalog();
        String acc = cat.account.id;
        Account other = CatalogService.createAccount(account("Nubank", "CHECKING", 0, "#123456"));
        tx("Pagamento", 80, "EXPENSE", "2027-04-01", acc);
        tx("Crédito João Silva", 80, "INCOME", "2027-04-01", other.id);
        tx("Envio para Nubank", 70, "EXPENSE", "2027-04-02", acc);
        tx("Entrada", 70, "INCOME", "2027-04-04", other.id);
        // Fora da janela de 3 dias.
        tx("Saída", 40, "EXPENSE", "2027-04-01", acc);
        tx("Entrada", 40, "INCOME", "2027-04-05", other.id);

        List<TransferSuggestion> list = TransfersService.transferSuggestions("João Silva").items;
        assertEquals(2, list.size());
        assertEquals("Envio para Nubank", list.get(0).from.description);
        assertEquals(100 - 2 * 15 + 20 + 15 + 5, list.get(0).score);
        assertEquals(100 + 10 + 5, list.get(1).score);
        assertEquals(105, TransfersService.transferSuggestions(null).items.get(1).score);
    }
}
