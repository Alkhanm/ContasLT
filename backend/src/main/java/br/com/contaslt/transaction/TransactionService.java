package br.com.contaslt.transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.contaslt.account.Account;
import br.com.contaslt.account.AccountDtos.AccountDto;
import br.com.contaslt.account.AccountService;
import br.com.contaslt.card.CardInvoiceDtos.CardInvoiceDto;
import br.com.contaslt.card.CardInvoiceService;
import br.com.contaslt.card.InvoicePeriod;
import br.com.contaslt.category.Category;
import br.com.contaslt.category.CategoryService;
import br.com.contaslt.common.BusinessException;
import br.com.contaslt.common.NotFoundException;
import br.com.contaslt.common.TransactionType;
import br.com.contaslt.recurring.RecurringBillService;
import br.com.contaslt.transaction.TransactionDtos.DayBalance;
import br.com.contaslt.transaction.TransactionDtos.EditScope;
import br.com.contaslt.transaction.TransactionDtos.ItemRequest;
import br.com.contaslt.transaction.TransactionDtos.Summary;
import br.com.contaslt.transaction.TransactionDtos.TransactionDto;
import br.com.contaslt.transaction.TransactionDtos.TransactionPage;
import br.com.contaslt.transaction.TransactionDtos.TransactionRequest;
import br.com.contaslt.transfer.Transfer;
import br.com.contaslt.transfer.TransferRepository;
import br.com.contaslt.user.User;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;

@Service
public class TransactionService {

    /** {@code transfers}: null = tudo, true = só pernas de transferência, false = sem transferências. */
    /**
     * {@code categoryIds}: a categoria filtrada e suas subcategorias (vazio/nulo = todas).
     * {@code uncategorized}: true = só lançamentos sem categoria.
     */
    /**
     * {@code invoice}: com uma conta de cartão de crédito filtrada, true troca o mês civil pelo período da fatura
     * que vence nesse mês (compras entre dois fechamentos).
     */
    public record Filter(YearMonth month, Long accountId, List<Long> categoryIds, Boolean uncategorized,
            TransactionType type, Boolean paid, String search, Boolean transfers, Boolean invoice) {
    }

    /** Intervalo de datas listado: mês civil ou período da fatura. */
    private record Range(LocalDate from, LocalDate to, Account card, InvoicePeriod invoice) {
    }

    private final TransactionRepository transactions;
    private final AccountService accounts;
    private final CategoryService categories;
    private final RecurringBillService recurring;
    private final TransferRepository transfers;
    private final CardInvoiceService cardInvoices;

    public TransactionService(TransactionRepository transactions, AccountService accounts,
            CategoryService categories, RecurringBillService recurring, TransferRepository transfers,
            CardInvoiceService cardInvoices) {
        this.transactions = transactions;
        this.accounts = accounts;
        this.categories = categories;
        this.recurring = recurring;
        this.transfers = transfers;
        this.cardInvoices = cardInvoices;
    }

    @Transactional
    public TransactionPage list(User user, Filter filter) {
        Range range = range(user.getId(), filter);
        for (YearMonth ym = YearMonth.from(range.from()); !ym.isAfter(YearMonth.from(range.to())); ym = ym.plusMonths(1)) {
            recurring.ensureOccurrences(user, ym);
        }
        CardInvoiceDto invoice = null;
        if (range.invoice() != null) {
            cardInvoices.refresh(user, range.card(), range.invoice().month());
            invoice = cardInvoices.summarize(range.card(), range.invoice().month());
        }
        List<Transaction> items = transactions.findAll(spec(user.getId(), filter, range),
            Sort.by(Sort.Order.desc("dueDate"), Sort.Order.desc("id")));
        List<AccountDto> scope = accountScope(user.getId(), filter);
        return new TransactionPage(items.stream().map(TransactionMapper::toDto).toList(),
            cash(summarize(items), scope, range), dayBalances(scope, range), invoice);
    }

    /** Contas do recorte de caixa: a filtrada, ou todas as ativas. */
    private List<AccountDto> accountScope(Long userId, Filter f) {
        return accounts.list(userId, true).stream()
            .filter(a -> f.accountId() != null ? a.id().equals(f.accountId()) : !a.archived())
            .toList();
    }

    /**
     * Plano de caixa do resumo: saldo das contas no início do período, entradas e saídas pagas (transferências
     * incluídas, como no extrato) e saldo ao fim; o previsto soma o que ainda está pendente no período.
     */
    private Summary cash(Summary result, List<AccountDto> scope, Range range) {
        if (scope.isEmpty()) {
            return result.withCash(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        List<Long> ids = scope.stream().map(AccountDto::id).toList();
        BigDecimal previous = scope.stream().map(AccountDto::initialBalance).reduce(BigDecimal.ZERO, BigDecimal::add)
            .add(transactions.sumPaidMovementsBefore(ids, range.from()));
        BigDecimal in = BigDecimal.ZERO;
        BigDecimal out = BigDecimal.ZERO;
        BigDecimal pendingNet = BigDecimal.ZERO;
        for (Object[] row : transactions.sumMovementsByTypeAndPaid(ids, range.from(), range.to())) {
            boolean income = row[0] == TransactionType.INCOME;
            BigDecimal amount = (BigDecimal) row[2];
            if (Boolean.TRUE.equals(row[1])) {
                if (income) {
                    in = in.add(amount);
                } else {
                    out = out.add(amount);
                }
            } else {
                pendingNet = income ? pendingNet.add(amount) : pendingNet.subtract(amount);
            }
        }
        return result.withCash(previous, in, out, pendingNet);
    }

    private Range range(Long userId, Filter f) {
        if (Boolean.TRUE.equals(f.invoice()) && f.accountId() != null) {
            Account account = accounts.require(userId, f.accountId());
            if (account.isCreditCard()) {
                InvoicePeriod p = InvoicePeriod.of(account, f.month());
                return new Range(p.start(), p.end(), account, p);
            }
        }
        return new Range(f.month().atDay(1), f.month().atEndOfMonth(), null, null);
    }

    /**
     * Saldo ao fim de cada dia do mês: saldo inicial das contas + movimentos pagos até o dia. Usa todas as contas
     * ativas, ou só a filtrada; ignora os outros filtros, porque o saldo da conta não depende do que está na lista.
     */
    private List<DayBalance> dayBalances(List<AccountDto> selected, Range range) {
        if (selected.isEmpty()) {
            return List.of();
        }
        List<Long> ids = selected.stream().map(AccountDto::id).toList();
        LocalDate from = range.from();
        LocalDate to = range.to();
        BigDecimal running = selected.stream().map(AccountDto::initialBalance).reduce(BigDecimal.ZERO, BigDecimal::add)
            .add(transactions.sumPaidMovementsBefore(ids, from));
        Map<LocalDate, BigDecimal> byDay = new HashMap<>();
        for (Object[] row : transactions.sumPaidMovementsByDay(ids, from, to)) {
            byDay.put((LocalDate) row[0], (BigDecimal) row[1]);
        }
        List<DayBalance> result = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            running = running.add(byDay.getOrDefault(day, BigDecimal.ZERO));
            result.add(new DayBalance(day, running));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public TransactionDto get(Long userId, Long id) {
        return TransactionMapper.toDto(require(userId, id));
    }

    @Transactional
    public TransactionDto create(User user, TransactionRequest req) {
        Transaction t = new Transaction();
        t.setUser(user);
        apply(user.getId(), t, req);
        transactions.save(t);
        cardInvoices.refreshFor(user, t.getAccount(), t.getDueDate());
        return TransactionMapper.toDto(t);
    }

    @Transactional
    public TransactionDto update(User user, Long id, TransactionRequest req, EditScope scope) {
        Transaction t = require(user.getId(), id);
        if (t.isInvoiceGap()) {
            // Só categoria e observações; o resto é calculado pela fatura.
            Category category = categories.requireOrNull(user.getId(), req.categoryId());
            if (category != null && category.getType() != TransactionType.EXPENSE) {
                throw new BusinessException("A categoria escolhida não é do mesmo tipo do lançamento");
            }
            t.setCategory(category);
            t.setNotes(req.notes());
            return TransactionMapper.toDto(t);
        }
        Account previousAccount = t.getAccount();
        LocalDate previousDate = t.getDueDate();
        LocalDate previousScheduled = t.getScheduledDate() != null ? t.getScheduledDate() : t.getDueDate();
        apply(user.getId(), t, req);
        if (scope == EditScope.FOLLOWING && t.getRecurringBill() != null) {
            recurring.applyToFollowing(user, t, previousScheduled);
        }
        cardInvoices.refreshFor(user, previousAccount, previousDate);
        cardInvoices.refreshFor(user, t.getAccount(), t.getDueDate());
        return TransactionMapper.toDto(t);
    }

    @Transactional
    public TransactionDto setPaid(Long userId, Long id, boolean paid) {
        Transaction t = require(userId, id);
        CardInvoiceService.rejectManualChange(t);
        t.setPaid(paid);
        return TransactionMapper.toDto(t);
    }

    @Transactional
    public void delete(User user, Long id) {
        Transaction t = require(user.getId(), id);
        CardInvoiceService.rejectManualChange(t);
        Transfer tr = t.getTransfer();
        YearMonth invoiceMonth = null;
        Account card = null;
        if (tr != null) {
            // Desfaz o vínculo antes: a outra perna continua existindo como lançamento comum.
            invoiceMonth = tr.getInvoiceMonth();
            card = tr.getToTransaction().getAccount();
            tr.getFromTransaction().setTransfer(null);
            tr.getToTransaction().setTransfer(null);
            transfers.delete(tr);
        }
        Account account = t.getAccount();
        LocalDate date = t.getDueDate();
        transactions.delete(t);
        transactions.flush();
        cardInvoices.refreshFor(user, account, date);
        if (invoiceMonth != null) {
            cardInvoices.refresh(user, card, invoiceMonth);
        }
    }

    public Transaction require(Long userId, Long id) {
        return transactions.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new NotFoundException("Lançamento não encontrado"));
    }

    public static Summary summarize(List<Transaction> items) {
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        BigDecimal pendingIncome = BigDecimal.ZERO;
        BigDecimal pendingExpense = BigDecimal.ZERO;
        BigDecimal transferOut = BigDecimal.ZERO;
        BigDecimal transferIn = BigDecimal.ZERO;
        for (Transaction t : items) {
            if (t.isTransfer()) {
                if (t.getType() == TransactionType.INCOME) {
                    transferIn = transferIn.add(t.getAmount());
                } else {
                    transferOut = transferOut.add(t.getAmount());
                }
                continue;
            }
            if (t.getType() == TransactionType.INCOME) {
                income = income.add(t.getAmount());
                if (!t.isPaid()) {
                    pendingIncome = pendingIncome.add(t.getAmount());
                }
            } else {
                expense = expense.add(t.getAmount());
                if (!t.isPaid()) {
                    pendingExpense = pendingExpense.add(t.getAmount());
                }
            }
        }
        BigDecimal balance = income.subtract(expense);
        return new Summary(income, expense, balance, pendingIncome, pendingExpense, transferOut,
            transferIn, items.size(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private void apply(Long userId, Transaction t, TransactionRequest req) {
        Account account = accounts.require(userId, req.accountId());
        Category category = categories.requireOrNull(userId, req.categoryId());
        if (t.isTransfer() && req.type() != t.getType()) {
            throw new BusinessException("Este lançamento faz parte de uma transferência; desvincule antes de mudar o tipo");
        }
        if (category != null && category.getType() != req.type()) {
            throw new BusinessException("A categoria escolhida não é do mesmo tipo do lançamento");
        }
        t.setDescription(req.description().trim());
        t.setAmount(req.amount());
        t.setType(req.type());
        t.setDueDate(req.dueDate());
        t.setPaid(req.paid() != null && req.paid());
        t.setNotes(req.notes());
        t.setAccount(account);
        t.setCategory(category);
        t.setMerchant(blankToNull(req.merchant()));
        t.setReceiptAccessKey(blankToNull(req.receiptAccessKey()));
        String receiptFile = blankToNull(req.receiptFile());
        if (receiptFile != null && !receiptFile.startsWith("u" + userId + "-")) {
            throw new BusinessException("Comprovante inválido");
        }
        t.setReceiptFile(receiptFile);
        List<TransactionItem> items = new ArrayList<>();
        if (req.items() != null) {
            for (ItemRequest i : req.items()) {
                TransactionItem item = new TransactionItem();
                item.setDescription(i.description().trim());
                item.setQuantity(i.quantity());
                item.setUnitPrice(i.unitPrice());
                item.setTotal(i.total());
                items.add(item);
            }
        }
        t.replaceItems(items);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Specification<Transaction> spec(Long userId, Filter f, Range range) {
        return (root, query, cb) -> {
            if (query != null && query.getResultType() == Transaction.class) {
                root.fetch("account", JoinType.LEFT);
                root.fetch("category", JoinType.LEFT);
                root.fetch("recurringBill", JoinType.LEFT);
                root.fetch("transfer", JoinType.LEFT);
            }
            List<Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("user").get("id"), userId));
            p.add(cb.between(root.get("dueDate"), range.from(), range.to()));
            if (f.accountId() != null) {
                p.add(cb.equal(root.get("account").get("id"), f.accountId()));
            }
            if (Boolean.TRUE.equals(f.uncategorized())) {
                p.add(cb.isNull(root.get("category")));
                if (f.transfers() == null) {
                    // Transferências nunca têm categoria; não são "sem categoria" a resolver.
                    p.add(cb.isNull(root.get("transfer")));
                }
            } else if (f.categoryIds() != null && !f.categoryIds().isEmpty()) {
                p.add(root.get("category").get("id").in(f.categoryIds()));
            }
            if (f.type() != null) {
                p.add(cb.equal(root.get("type"), f.type()));
            }
            if (f.paid() != null) {
                p.add(cb.equal(root.get("paid"), f.paid()));
            }
            if (f.transfers() != null) {
                p.add(f.transfers() ? cb.isNotNull(root.get("transfer")) : cb.isNull(root.get("transfer")));
            }
            if (f.search() != null && !f.search().isBlank()) {
                p.add(cb.like(cb.lower(root.get("description")), "%" + f.search().trim().toLowerCase() + "%"));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
    }
}
