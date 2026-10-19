package br.com.contaslt.transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.contaslt.account.Account;
import br.com.contaslt.account.AccountService;
import br.com.contaslt.category.Category;
import br.com.contaslt.category.CategoryService;
import br.com.contaslt.common.BusinessException;
import br.com.contaslt.common.NotFoundException;
import br.com.contaslt.common.TransactionType;
import br.com.contaslt.recurring.RecurringBillService;
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
    public record Filter(YearMonth month, Long accountId, Long categoryId, TransactionType type, Boolean paid,
            String search, Boolean transfers) {
    }

    private final TransactionRepository transactions;
    private final AccountService accounts;
    private final CategoryService categories;
    private final RecurringBillService recurring;
    private final TransferRepository transfers;

    public TransactionService(TransactionRepository transactions, AccountService accounts,
            CategoryService categories, RecurringBillService recurring, TransferRepository transfers) {
        this.transactions = transactions;
        this.accounts = accounts;
        this.categories = categories;
        this.recurring = recurring;
        this.transfers = transfers;
    }

    @Transactional
    public TransactionPage list(User user, Filter filter) {
        recurring.ensureOccurrences(user, filter.month());
        List<Transaction> items = transactions.findAll(spec(user.getId(), filter),
            Sort.by(Sort.Order.desc("dueDate"), Sort.Order.desc("id")));
        return new TransactionPage(items.stream().map(TransactionMapper::toDto).toList(), summarize(items));
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
    public TransactionDto update(Long userId, Long id, TransactionRequest req) {
        Transaction t = require(userId, id);
        apply(userId, t, req);
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
        return new Summary(income, expense, income.subtract(expense), pendingIncome, pendingExpense, transferOut,
            transferIn, items.size());
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
    }

    private static Specification<Transaction> spec(Long userId, Filter f) {
        return (root, query, cb) -> {
            if (query != null && query.getResultType() != Long.class && query.getResultType() != long.class) {
                root.fetch("account", JoinType.LEFT);
                root.fetch("category", JoinType.LEFT);
                root.fetch("recurringBill", JoinType.LEFT);
                root.fetch("transfer", JoinType.LEFT);
            }
            List<Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("user").get("id"), userId));
            LocalDate from = f.month().atDay(1);
            LocalDate to = f.month().atEndOfMonth();
            p.add(cb.between(root.get("dueDate"), from, to));
            if (f.accountId() != null) {
                p.add(cb.equal(root.get("account").get("id"), f.accountId()));
            }
            if (f.categoryId() != null) {
                p.add(cb.equal(root.get("category").get("id"), f.categoryId()));
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
