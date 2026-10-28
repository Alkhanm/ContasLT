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
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

@Service
public class TransactionService {

    /** {@code transfers}: null = tudo, true = só pernas de transferência, false = sem transferências. */
    /**
     * {@code categoryIds}: a categoria filtrada e suas subcategorias (vazio/nulo = todas).
     * {@code uncategorized}: true = só lançamentos sem categoria.
     */
    public record Filter(YearMonth month, Long accountId, List<Long> categoryIds, Boolean uncategorized,
            TransactionType type, Boolean paid, String search, Boolean transfers) {
    }

    private final TransactionRepository transactions;
    private final AccountService accounts;
    private final CategoryService categories;
    private final RecurringBillService recurring;
    private final TransferRepository transfers;
    private final EntityManager em;

    public TransactionService(TransactionRepository transactions, AccountService accounts,
            CategoryService categories, RecurringBillService recurring, TransferRepository transfers,
            EntityManager em) {
        this.transactions = transactions;
        this.accounts = accounts;
        this.categories = categories;
        this.recurring = recurring;
        this.transfers = transfers;
        this.em = em;
    }

    @Transactional
    public TransactionPage list(User user, Filter filter) {
        recurring.ensureOccurrences(user, filter.month());
        List<Transaction> items = transactions.findAll(spec(user.getId(), filter),
            Sort.by(Sort.Order.desc("dueDate"), Sort.Order.desc("id")));
        Summary summary = summarize(items).withPreviousBalance(previousBalance(user.getId(), filter));
        return new TransactionPage(items.stream().map(TransactionMapper::toDto).toList(), summary,
            dayBalances(user.getId(), filter));
    }

    /**
     * Saldo anterior: receitas − despesas de todos os meses antes do mês listado, com os mesmos filtros da lista
     * (conta, categoria, situação, busca). Pernas de transferência ficam de fora, como no resultado do mês.
     */
    private BigDecimal previousBalance(Long userId, Filter f) {
        if (Boolean.TRUE.equals(f.transfers())) {
            return BigDecimal.ZERO;
        }
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<BigDecimal> q = cb.createQuery(BigDecimal.class);
        Root<Transaction> root = q.from(Transaction.class);
        Predicate filters = spec(userId, f, false).toPredicate(root, q, cb);
        var signed = cb.<BigDecimal>selectCase()
            .when(cb.equal(root.get("type"), TransactionType.INCOME), root.<BigDecimal>get("amount"))
            .otherwise(cb.neg(root.<BigDecimal>get("amount")));
        q.select(cb.coalesce(cb.sum(signed), BigDecimal.ZERO))
            .where(filters, cb.isNull(root.get("transfer")));
        return em.createQuery(q).getSingleResult();
    }

    /**
     * Saldo ao fim de cada dia do mês: saldo inicial das contas + movimentos pagos até o dia. Usa todas as contas
     * ativas, ou só a filtrada; ignora os outros filtros, porque o saldo da conta não depende do que está na lista.
     */
    private List<DayBalance> dayBalances(Long userId, Filter f) {
        List<AccountDto> selected = accounts.list(userId, true).stream()
            .filter(a -> f.accountId() != null ? a.id().equals(f.accountId()) : !a.archived())
            .toList();
        if (selected.isEmpty()) {
            return List.of();
        }
        List<Long> ids = selected.stream().map(AccountDto::id).toList();
        LocalDate from = f.month().atDay(1);
        LocalDate to = f.month().atEndOfMonth();
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
        return TransactionMapper.toDto(transactions.save(t));
    }

    @Transactional
    public TransactionDto update(User user, Long id, TransactionRequest req, EditScope scope) {
        Transaction t = require(user.getId(), id);
        LocalDate previousScheduled = t.getScheduledDate() != null ? t.getScheduledDate() : t.getDueDate();
        apply(user.getId(), t, req);
        if (scope == EditScope.FOLLOWING && t.getRecurringBill() != null) {
            recurring.applyToFollowing(user, t, previousScheduled);
        }
        return TransactionMapper.toDto(t);
    }

    @Transactional
    public TransactionDto setPaid(Long userId, Long id, boolean paid) {
        Transaction t = require(userId, id);
        t.setPaid(paid);
        return TransactionMapper.toDto(t);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        Transaction t = require(userId, id);
        Transfer tr = t.getTransfer();
        if (tr != null) {
            // Desfaz o vínculo antes: a outra perna continua existindo como lançamento comum.
            tr.getFromTransaction().setTransfer(null);
            tr.getToTransaction().setTransfer(null);
            transfers.delete(tr);
        }
        transactions.delete(t);
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
            transferIn, items.size(), BigDecimal.ZERO, balance);
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

    private static Specification<Transaction> spec(Long userId, Filter f) {
        return spec(userId, f, true);
    }

    /** {@code inMonth}: true = lançamentos do mês filtrado; false = tudo que vence antes dele (saldo anterior). */
    private static Specification<Transaction> spec(Long userId, Filter f, boolean inMonth) {
        return (root, query, cb) -> {
            if (query != null && query.getResultType() == Transaction.class) {
                root.fetch("account", JoinType.LEFT);
                root.fetch("category", JoinType.LEFT);
                root.fetch("recurringBill", JoinType.LEFT);
                root.fetch("transfer", JoinType.LEFT);
            }
            List<Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("user").get("id"), userId));
            LocalDate from = f.month().atDay(1);
            if (inMonth) {
                p.add(cb.between(root.get("dueDate"), from, f.month().atEndOfMonth()));
            } else {
                p.add(cb.lessThan(root.get("dueDate"), from));
            }
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
