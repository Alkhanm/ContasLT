package br.com.contaslt.services;

import static br.com.contaslt.services.Testing.account;
import static br.com.contaslt.services.Testing.cents;
import static br.com.contaslt.services.Testing.tx;
import static org.junit.Assert.assertEquals;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import br.com.contaslt.data.schema.Entity;
import br.com.contaslt.data.schema.TransactionRec;
import br.com.contaslt.data.schema.TransferRec;
import br.com.contaslt.domain.Ids;
import br.com.contaslt.dto.Account;
import br.com.contaslt.dto.AccountInput;
import br.com.contaslt.dto.DayBalance;
import br.com.contaslt.dto.Summary;
import br.com.contaslt.dto.TransactionFilter;
import br.com.contaslt.dto.TransactionPage;

/** Porte de frontend/src/services/daybalance.test.ts (DayBalanceTest). */
public class DayBalanceTest {

    @Before
    public void setUp() {
        Testing.freshStore();
    }

    private static void transaction(String accountId, String description, double amount, String type, String dueDate,
                                    boolean paid) {
        TransactionsService.createTransaction(tx(description, amount, type, dueDate, paid, accountId, null));
    }

    /** Transferência gravada direto no store (o serviço de transferências é testado à parte). */
    private static void transfer(String fromAccountId, String toAccountId, double amount, String date, String description) {
        String id = Ids.newId();
        TransactionRec from = leg(fromAccountId, "EXPENSE", amount, date, description, id);
        TransactionRec to = leg(toAccountId, "INCOME", amount, date, description, id);
        Ctx.db().put(Entity.TRANSACTION, from);
        Ctx.db().put(Entity.TRANSACTION, to);
        TransferRec tr = new TransferRec();
        tr.id = id;
        tr.fromTransactionId = from.id;
        tr.toTransactionId = to.id;
        tr.createdAt = from.createdAt;
        Ctx.db().put(Entity.TRANSFER, tr);
    }

    private static TransactionRec leg(String accountId, String type, double amount, String date, String description,
                                      String transferId) {
        TransactionRec t = TransactionsService.emptyTransaction();
        t.accountId = accountId;
        t.type = type;
        t.description = description;
        t.amount = cents(amount);
        t.dueDate = date;
        t.paid = true;
        t.transferId = transferId;
        return t;
    }

    private static long at(List<DayBalance> days, String date) {
        for (DayBalance d : days) {
            if (d.date.equals(date)) {
                return d.balance;
            }
        }
        throw new AssertionError("sem saldo para " + date);
    }

    private static TransactionPage list(String month, String accountId, Boolean paid) {
        TransactionFilter f = new TransactionFilter();
        f.month = month;
        f.accountId = accountId;
        f.paid = paid;
        return TransactionsService.listTransactions(f);
    }

    @Test
    public void saldoAcumuladoContaSoOPago() {
        Account account = CatalogService.createAccount(account("Conta principal", "CHECKING", 0, "#1B5E3F"));
        long initial = account.initialBalance;

        transaction(account.id, "Anterior", 100, "INCOME", "2032-01-20", true); // mês anterior: entra no ponto de partida
        transaction(account.id, "Salário", 1000, "INCOME", "2032-02-05", true);
        transaction(account.id, "Luz", 200, "EXPENSE", "2032-02-05", true);
        transaction(account.id, "Boleto a pagar", 999, "EXPENSE", "2032-02-10", false); // pendente: não conta
        transaction(account.id, "Mercado", 50, "EXPENSE", "2032-02-15", true);

        List<DayBalance> days = list("2032-02", null, null).dayBalances;
        assertEquals(29, days.size()); // fevereiro de 2032 é bissexto
        assertEquals(initial + 10000, at(days, "2032-02-01"));
        assertEquals(initial + 10000 + 100000 - 20000, at(days, "2032-02-05"));
        assertEquals(initial + 90000, at(days, "2032-02-10"));
        assertEquals(initial + 85000, at(days, "2032-02-15"));
        assertEquals(initial + 85000, at(days, "2032-02-29"));
    }

    @Test
    public void resumoSegueOExtrato() {
        String pfId = CatalogService.createAccount(account("Conta principal", "CHECKING", 0, "#1B5E3F")).id;
        String pjId = CatalogService.createAccount(account("PJ", "CHECKING", 50000, "#1B5E3F")).id;

        transaction(pjId, "Cliente jan", 1000, "INCOME", "2033-01-05", true);
        transaction(pfId, "Mercado jan", 200, "EXPENSE", "2033-01-10", true);
        // Fevereiro: pró-labore PJ → PF (transferência), uma despesa paga e uma pendente na PF.
        transfer(pjId, pfId, 800, "2033-02-05", "Pró-labore");
        transaction(pfId, "Aluguel fev", 700, "EXPENSE", "2033-02-10", true);
        transaction(pfId, "Boleto fev", 50, "EXPENSE", "2033-02-20", false);

        TransactionPage page = list("2033-02", pfId, null);
        Summary pf = page.summary;
        assertEquals(-20000, pf.previousBalance);
        assertEquals(80000, pf.transfer);
        assertEquals(0, pf.income);
        assertEquals(70000, pf.expense);
        assertEquals(-10000, pf.balance);
        assertEquals(5000, pf.pendingExpense);
        assertEquals(-15000, pf.projectedBalance);
        assertEquals(3, pf.count);
        assertEquals(-10000, at(page.dayBalances, "2033-02-28"));

        // O resumo é da conta, não da lista filtrada: filtrando "pendentes", só a contagem muda.
        Summary pending = list("2033-02", pfId, false).summary;
        assertEquals(-10000, pending.balance);
        assertEquals(70000, pending.expense);
        assertEquals(1, pending.count);

        // Todas as contas: as duas pernas da transferência se anulam; o anterior inclui o saldo inicial da PJ.
        Summary all = list("2033-02", null, null).summary;
        assertEquals(50000 + 100000 - 20000, all.previousBalance);
        assertEquals(0, all.transfer);
        assertEquals(70000, all.expense);
        assertEquals(60000, all.balance);
        assertEquals(55000, all.projectedBalance);

        // Saldo das contas considera as pernas da transferência.
        Map<String, Long> balances = new HashMap<>();
        for (Account a : CatalogService.listAccounts()) {
            balances.put(a.id, a.balance);
        }
        assertEquals(Long.valueOf(50000 + 100000 - 80000), balances.get(pjId));
        assertEquals(Long.valueOf(-20000 + 80000 - 70000), balances.get(pfId));
    }

    @Test
    public void contasArquivadasECartoesFicamForaDoRecorte() {
        Account a = CatalogService.createAccount(account("A", "CHECKING", 10000, "#1B5E3F"));
        AccountInput old = account("Velha", "SAVINGS", 100000, "#1B5E3F");
        old.archived = true;
        CatalogService.createAccount(old);
        Account card = CatalogService.createAccount(Testing.card("Cartão", 0, "#1B5E3F", 25, 5));
        transaction(card.id, "Compra", 40, "EXPENSE", "2033-02-03", true);
        TransactionPage page = list("2033-02", null, null);
        assertEquals(10000, page.summary.previousBalance);
        assertEquals(0, page.summary.expense);
        assertEquals(10000, at(page.dayBalances, "2033-02-28"));
        // Filtrando uma conta, mesmo arquivada, o recorte é ela.
        assertEquals(10000, list("2033-02", a.id, null).summary.previousBalance);
    }
}
