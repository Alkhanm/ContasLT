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
import br.com.contaslt.user.User;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;

@Service
public class TransactionService {

    public record Filter(YearMonth month, Long accountId, Long categoryId, TransactionType type, Boolean paid,
            String search) {
    }

    private final TransactionRepository transactions;
    private final AccountService accounts;
    private final CategoryService categories;
    private final RecurringBillService recurring;

    public TransactionService(TransactionRepository transactions, AccountService accounts,
            CategoryService categories, RecurringBillService recurring) {
        this.transactions = transactions;
        this.accounts = accounts;
        this.categories = categories;
        this.recurring = recurring;
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
        transactions.delete(require(userId, id));
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
        for (Transaction t : items) {
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
        return new Summary(income, expense, income.subtract(expense), pendingIncome, pendingExpense, items.size());
    }

    private void apply(Long userId, Transaction t, TransactionRequest req) {
        Account account = accounts.require(userId, req.accountId());
        Category category = categories.requireOrNull(userId, req.categoryId());
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
            if (f.search() != null && !f.search().isBlank()) {
                p.add(cb.like(cb.lower(root.get("description")), "%" + f.search().trim().toLowerCase() + "%"));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
    }
}
