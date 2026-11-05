package br.com.contaslt.services;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import br.com.contaslt.data.LocalStore;
import br.com.contaslt.data.schema.AccountRec;
import br.com.contaslt.data.schema.CategoryRec;
import br.com.contaslt.data.schema.Entity;
import br.com.contaslt.data.schema.ItemRec;
import br.com.contaslt.data.schema.TransactionRec;
import br.com.contaslt.data.schema.TransferRec;
import br.com.contaslt.domain.Dates;
import br.com.contaslt.domain.Ids;
import br.com.contaslt.domain.Invoice;
import br.com.contaslt.domain.Text;
import br.com.contaslt.dto.DayBalance;
import br.com.contaslt.dto.Summary;
import br.com.contaslt.dto.Transaction;
import br.com.contaslt.dto.TransactionFilter;
import br.com.contaslt.dto.TransactionInput;
import br.com.contaslt.dto.TransactionItemInput;
import br.com.contaslt.dto.TransactionPage;

/**
 * Lançamentos (porte de frontend/src/services/transactions.ts): lista do mês com resumo e saldo por dia, CRUD e escopo
 * de edição.
 */
public final class TransactionsService {

    /** Alcance de uma edição em ocorrência de conta recorrente (TransactionEditScope). */
    public static final String SCOPE_THIS = "THIS";
    public static final String SCOPE_FOLLOWING = "FOLLOWING";

    private TransactionsService() {
    }

    private static final class Range {
        final String from;
        final String to;
        final AccountRec card;

        Range(String from, String to, AccountRec card) {
            this.from = from;
            this.to = to;
            this.card = card;
        }
    }

    private static Range range(TransactionFilter f) {
        if (Boolean.TRUE.equals(f.invoice) && Text.present(f.accountId)) {
            AccountRec account = Ctx.require(Ctx.db().get(Entity.ACCOUNT, f.accountId), "Conta não encontrada");
            if (CardsService.isCreditCard(account)) {
                Invoice.Period p = Invoice.invoiceOf(account, f.month);
                return new Range(p.start(), p.end(), account);
            }
        }
        return new Range(Dates.firstDay(f.month), Dates.endOfMonth(f.month), null);
    }

    /** Contas do recorte de caixa: a filtrada, ou todas as ativas de dinheiro (cartão não é caixa). */
    private static List<AccountRec> accountScope(TransactionFilter f) {
        List<AccountRec> out = new ArrayList<>();
        for (AccountRec a : Ctx.db().list(Entity.ACCOUNT)) {
            if (Text.present(f.accountId) ? a.id.equals(f.accountId) : !a.archived && !CardsService.isCreditCard(a)) {
                out.add(a);
            }
        }
        return out;
    }

    /** Comparação de texto como o {@code <}/{@code >} do JavaScript: com nulo, empate. */
    private static int cmp(String a, String b) {
        if (a == null || b == null) {
            return 0;
        }
        return Integer.signum(a.compareTo(b));
    }

    /** Data decrescente; empate: criado por último primeiro. */
    public static int byDateDesc(TransactionRec a, TransactionRec b) {
        int byDate = cmp(b.dueDate, a.dueDate);
        return byDate != 0 ? byDate : cmp(b.createdAt, a.createdAt);
    }

    public static TransactionPage listTransactions(TransactionFilter f) {
        Range r = range(f);
        RecurringService.ensureRange(r.from, r.to);
        LocalStore s = Ctx.db();
        List<TransactionRec> txs = s.list(Entity.TRANSACTION);
        boolean uncategorized = Boolean.TRUE.equals(f.uncategorized);
        Set<String> categoryIds = !uncategorized && Text.present(f.categoryId)
            ? new HashSet<>(CatalogService.idsWithChildren(f.categoryId)) : null;
        String search = f.search == null ? null : Text.trim(f.search).toLowerCase(Locale.ROOT);
        if (search != null && search.isEmpty()) {
            search = null;
        }

        List<TransactionRec> items = new ArrayList<>();
        for (TransactionRec t : txs) {
            if (t.dueDate.compareTo(r.from) < 0 || t.dueDate.compareTo(r.to) > 0) {
                continue;
            }
            if (Text.present(f.accountId)) {
                if (!t.accountId.equals(f.accountId)) {
                    continue;
                }
            } else if (CardsService.isCreditCard(s.get(Entity.ACCOUNT, t.accountId))) {
                // Compras de cartão só aparecem na visão do próprio cartão; na lista geral entra a fatura paga.
                continue;
            }
            if (uncategorized) {
                if (Text.present(t.categoryId)) {
                    continue;
                }
                // Transferências nunca têm categoria; não são "sem categoria" a resolver.
                if (f.transfers == null && Text.present(t.transferId)) {
                    continue;
                }
            } else if (categoryIds != null && (!Text.present(t.categoryId) || !categoryIds.contains(t.categoryId))) {
                continue;
            }
            if (Text.present(f.type) && !f.type.equals(t.type)) {
                continue;
            }
            if (f.paid != null && t.paid != f.paid) {
                continue;
            }
            if (f.transfers != null && (f.transfers ? !Text.present(t.transferId) : Text.present(t.transferId))) {
                continue;
            }
            if (search != null && !t.description.toLowerCase(Locale.ROOT).contains(search)) {
                continue;
            }
            items.add(t);
        }
        items.sort(TransactionsService::byDateDesc);

        List<AccountRec> scope = accountScope(f);
        Set<String> scopeIds = new HashSet<>();
        for (AccountRec a : scope) {
            scopeIds.add(a.id);
        }
        // Saldo anterior ao período: saldo inicial das contas + tudo o que foi pago antes.
        long previous = 0;
        if (!scope.isEmpty()) {
            for (AccountRec a : scope) {
                previous += a.initialBalance;
            }
            for (TransactionRec t : txs) {
                if (scopeIds.contains(t.accountId) && t.paid && t.dueDate.compareTo(r.from) < 0) {
                    previous += t.signed();
                }
            }
        }
        TransactionPage page = new TransactionPage();
        for (TransactionRec t : items) {
            page.items.add(Mappers.transactionDto(t, true, txs));
        }
        page.summary = summarize(items.size(), scopeIds, r, previous, txs);
        page.dayBalances = dayBalances(scopeIds, r, previous, txs);
        page.invoice = r.card != null ? CardsService.summarizeInvoice(r.card, f.month, txs) : null;
        return page;
    }

    /** Resumo como o extrato: só o pago entra; o pendente fica à parte e vai para o saldo previsto. */
    private static Summary summarize(int count, Set<String> scopeIds, Range r, long previous, List<TransactionRec> txs) {
        long transfer = 0, income = 0, expense = 0, pendingTransfer = 0, pendingIncome = 0, pendingExpense = 0;
        for (TransactionRec t : txs) {
            if (!scopeIds.contains(t.accountId) || t.dueDate.compareTo(r.from) < 0 || t.dueDate.compareTo(r.to) > 0) {
                continue;
            }
            if (Text.present(t.transferId)) {
                if (t.paid) {
                    transfer += t.signed();
                } else {
                    pendingTransfer += t.signed();
                }
            } else if ("INCOME".equals(t.type)) {
                if (t.paid) {
                    income += t.amount;
                } else {
                    pendingIncome += t.amount;
                }
            } else if (t.paid) {
                expense += t.amount;
            } else {
                pendingExpense += t.amount;
            }
        }
        long balance = previous + transfer + income - expense;
        long projected = balance + pendingTransfer + pendingIncome - pendingExpense;
        Summary sm = new Summary();
        sm.previousBalance = previous;
        sm.transfer = transfer;
        sm.income = income;
        sm.expense = expense;
        sm.balance = balance;
        sm.pendingTransfer = pendingTransfer;
        sm.pendingIncome = pendingIncome;
        sm.pendingExpense = pendingExpense;
        sm.projectedBalance = projected;
        sm.count = count;
        return sm;
    }

    /** Saldo ao fim de cada dia do período, só com o que está pago, nas contas do recorte. */
    private static List<DayBalance> dayBalances(Set<String> scopeIds, Range r, long previous, List<TransactionRec> txs) {
        List<DayBalance> out = new ArrayList<>();
        if (scopeIds.isEmpty()) {
            return out;
        }
        Map<String, Long> byDay = new HashMap<>();
        for (TransactionRec t : txs) {
            if (!scopeIds.contains(t.accountId) || !t.paid || t.dueDate.compareTo(r.from) < 0
                || t.dueDate.compareTo(r.to) > 0) {
                continue;
            }
            Long prev = byDay.get(t.dueDate);
            byDay.put(t.dueDate, (prev == null ? 0 : prev) + t.signed());
        }
        long running = previous;
        for (String day = r.from; day.compareTo(r.to) <= 0; day = Dates.plusDays(day, 1)) {
            Long v = byDay.get(day);
            running += v == null ? 0 : v;
            DayBalance d = new DayBalance();
            d.date = day;
            d.balance = running;
            out.add(d);
        }
        return out;
    }

    public static TransactionRec requireTransaction(String id) {
        return Ctx.require(Ctx.db().get(Entity.TRANSACTION, id), "Lançamento não encontrado");
    }

    public static Transaction getTransaction(String id) {
        return Mappers.transactionDto(requireTransaction(id), true, null);
    }

    private static int len(String s) {
        return s == null ? 0 : s.length();
    }

    private static void checkInput(TransactionInput req) {
        String description = req.description == null ? "" : Text.trim(req.description);
        List<TransactionItemInput> items = req.items == null ? new ArrayList<>() : req.items;
        Ctx.Checks checks = Ctx.validate()
            .check("description", !description.isEmpty(), "Informe a descrição")
            .check("description", description.length() <= 140, "Descrição muito longa")
            .check("amount", req.amount != null, "Informe o valor")
            .check("amount", req.amount == null || req.amount > 0, "O valor deve ser maior que zero")
            .check("type", Text.present(req.type), "Informe o tipo")
            .check("dueDate", Text.present(req.dueDate), "Informe a data")
            .check("notes", len(req.notes) <= 500, "Observação muito longa")
            .check("accountId", req.accountId != null, "Escolha a conta")
            .check("merchant", len(req.merchant) <= 120, "Estabelecimento muito longo")
            .check("receiptAccessKey", len(req.receiptAccessKey) <= 44, "Chave inválida")
            .check("items", items.size() <= 200, "Máximo de 200 itens");
        for (int n = 0; n < items.size(); n++) {
            TransactionItemInput i = items.get(n);
            checks.check("items[" + n + "].description", i.description != null && !Text.trim(i.description).isEmpty(),
                "Informe a descrição do item");
        }
        for (int n = 0; n < items.size(); n++) {
            checks.check("items[" + n + "].total", items.get(n).total != null, "Informe o valor do item");
        }
        checks.throwIfAny();
    }

    /** Aplica o formulário no registro. Devolve se a foto do comprovante mudou (e ficou uma). */
    private static boolean applyInput(TransactionRec t, TransactionInput req) {
        checkInput(req);
        AccountRec account = Ctx.require(Ctx.db().get(Entity.ACCOUNT, req.accountId), "Conta não encontrada");
        CategoryRec category = CatalogService.requireCategoryOrNull(req.categoryId);
        if (Text.present(t.transferId) && !Objects.equals(req.type, t.type)) {
            throw new Ctx.BusinessException(
                "Este lançamento faz parte de uma transferência; desvincule antes de mudar o tipo");
        }
        if (category != null && !category.type.equals(req.type)) {
            throw new Ctx.BusinessException("A categoria escolhida não é do mesmo tipo do lançamento");
        }
        t.description = Text.trim(req.description);
        t.amount = req.amount;
        t.type = req.type;
        t.dueDate = req.dueDate;
        t.paid = req.paid;
        t.notes = req.notes;
        t.accountId = account.id;
        t.categoryId = category == null ? null : category.id;
        applyInvoiceLink(t, req, account);
        t.merchant = Text.blankToNull(req.merchant);
        t.receiptAccessKey = Text.blankToNull(req.receiptAccessKey);
        String receiptFile = Text.blankToNull(req.receiptFile);
        boolean receiptChanged = !Objects.equals(t.receiptFile, receiptFile);
        if (receiptChanged) {
            clearReceiptScan(t);
        }
        t.receiptFile = receiptFile;
        List<ItemRec> items = new ArrayList<>();
        if (req.items != null) {
            for (TransactionItemInput i : req.items) {
                items.add(new ItemRec(Text.trim(i.description), i.quantity, i.unitPrice, i.total));
            }
        }
        t.items = items;
        return receiptChanged && receiptFile != null;
    }

    public static void clearReceiptScan(TransactionRec t) {
        t.receiptScanStatus = null;
        t.receiptScanTotal = null;
        t.receiptScanDate = null;
    }

    /** Pagamento de fatura: despesa numa conta de dinheiro ligada a um cartão e a um mês de fatura. */
    private static void applyInvoiceLink(TransactionRec t, TransactionInput req, AccountRec account) {
        if (!Text.present(req.invoiceCardId)) {
            t.cardAccountId = null;
            t.cardInvoiceMonth = null;
            return;
        }
        if (!"EXPENSE".equals(req.type)) {
            throw new Ctx.BusinessException("Pagamento de fatura precisa ser uma despesa");
        }
        if (CardsService.isCreditCard(account)) {
            throw new Ctx.BusinessException("O pagamento da fatura sai de uma conta de dinheiro, não do próprio cartão");
        }
        AccountRec card = CardsService.requireCard(req.invoiceCardId);
        String month = req.invoiceMonth != null ? req.invoiceMonth : Invoice.inferInvoiceMonth(card, req.dueDate);
        t.cardAccountId = card.id;
        t.cardInvoiceMonth = Dates.firstDay(Dates.monthOf(month + "-01"));
    }

    public static TransactionRec emptyTransaction() {
        TransactionRec t = new TransactionRec();
        t.id = Ids.newId();
        t.accountId = "";
        t.categoryId = null;
        t.recurringBillId = null;
        t.importBatchId = null;
        t.transferId = null;
        t.description = "";
        t.amount = 0;
        t.type = "EXPENSE";
        t.dueDate = "";
        t.scheduledDate = null;
        t.paid = false;
        t.notes = null;
        t.externalId = null;
        t.installmentNumber = null;
        t.merchant = null;
        t.receiptFile = null;
        t.receiptAccessKey = null;
        t.linkedImport = false;
        t.receiptScanStatus = null;
        t.receiptScanTotal = null;
        t.receiptScanDate = null;
        t.cardAccountId = null;
        t.cardInvoiceMonth = null;
        t.items = new ArrayList<>();
        t.createdAt = Dates.nowIso();
        return t;
    }

    public static final class SaveResult {
        public final Transaction transaction;
        /** A foto do comprovante foi trocada: a leitura pendente dela deve ser ligada a este lançamento. */
        public final TransactionRec receiptAttached;

        public SaveResult(Transaction transaction, TransactionRec receiptAttached) {
            this.transaction = transaction;
            this.receiptAttached = receiptAttached;
        }
    }

    public static SaveResult createTransaction(TransactionInput req) {
        TransactionRec t = emptyTransaction();
        boolean receiptChanged = applyInput(t, req);
        Ctx.db().put(Entity.TRANSACTION, t);
        return new SaveResult(Mappers.transactionDto(t, true, null), receiptChanged ? t : null);
    }

    public static SaveResult updateTransaction(String id, TransactionInput req) {
        return updateTransaction(id, req, null);
    }

    /** {@code scope}: {@link #SCOPE_THIS}, {@link #SCOPE_FOLLOWING} ou null. */
    public static SaveResult updateTransaction(String id, TransactionInput req, String scope) {
        TransactionRec t = requireTransaction(id).copy();
        String previousScheduled = t.scheduledDate != null ? t.scheduledDate : t.dueDate;
        boolean receiptChanged = applyInput(t, req);
        Ctx.db().put(Entity.TRANSACTION, t);
        if (SCOPE_FOLLOWING.equals(scope) && Text.present(t.recurringBillId)) {
            t = RecurringService.applyToFollowing(t, previousScheduled);
        }
        return new SaveResult(Mappers.transactionDto(t, true, null), receiptChanged ? t : null);
    }

    public static Transaction setPaid(String id, boolean paid) {
        TransactionRec t = requireTransaction(id).copy();
        t.paid = paid;
        Ctx.db().put(Entity.TRANSACTION, t);
        return Mappers.transactionDto(t, true, null);
    }

    public static void deleteTransaction(String id) {
        LocalStore s = Ctx.db();
        TransactionRec t = requireTransaction(id);
        if (Text.present(t.transferId)) {
            // Desfaz o vínculo antes: a outra perna continua existindo como lançamento comum.
            TransferRec tr = s.get(Entity.TRANSFER, t.transferId);
            if (tr != null) {
                for (String legId : new String[] {tr.fromTransactionId, tr.toTransactionId}) {
                    TransactionRec leg = s.get(Entity.TRANSACTION, legId);
                    if (leg != null && !leg.id.equals(id)) {
                        TransactionRec next = leg.copy();
                        next.transferId = null;
                        s.put(Entity.TRANSACTION, next);
                    }
                }
                s.remove(Entity.TRANSFER, tr.id);
            }
        }
        // Vencimento apagado: o gerador volta a conferir o mês (como no servidor, ele reaparece).
        if (Text.present(t.recurringBillId)) {
            RecurringService.invalidateEnsured();
        }
        s.remove(Entity.TRANSACTION, id);
    }

    /**
     * Aviso de leitura adiada: {@code "ACCEPT"} usa total/data do cupom, {@code "DISMISS"} mantém o lançado. ("Ler de
     * novo" passa pelo servidor.)
     */
    public static Transaction resolveReceiptScan(String id, String action) {
        TransactionRec t = requireTransaction(id).copy();
        if ("ACCEPT".equals(action)) {
            if (!"DIVERGENT".equals(t.receiptScanStatus)) {
                throw new Ctx.BusinessException("Este lançamento não tem leitura divergente para aplicar");
            }
            if (t.receiptScanTotal != null) {
                t.amount = t.receiptScanTotal;
            }
            if (t.receiptScanDate != null) {
                t.dueDate = t.receiptScanDate;
            }
        }
        clearReceiptScan(t);
        Ctx.db().put(Entity.TRANSACTION, t);
        return Mappers.transactionDto(t, true, null);
    }
}
