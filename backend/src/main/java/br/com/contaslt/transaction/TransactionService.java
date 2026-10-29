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
import br.com.contaslt.account.AccountType;
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
        CardInvoiceDto invoice = range.invoice() == null ? null
            : cardInvoices.summarize(range.card(), range.invoice().month());
        List<Transaction> items = transactions.findAll(spec(user.getId(), filter, range),
            Sort.by(Sort.Order.desc("dueDate"), Sort.Order.desc("id")));
        List<AccountDto> scope = accountScope(user.getId(), filter);
        return new TransactionPage(items.stream().map(this::toDto).toList(),
            summarize(items.size(), scope, range), dayBalances(scope, range), invoice);
    }

    /** DTO com o resumo da fatura agregada, quando o lançamento é um pagamento de fatura. */
    private TransactionDto toDto(Transaction t) {
        TransactionDto dto = TransactionMapper.toDto(t);
        if (t.isInvoicePayment() && t.getCardInvoiceMonth() != null) {
            dto = dto.withInvoice(cardInvoices.summarize(t.getCardAccount(), t.getCardInvoiceMonth()));
        }
        return dto;
    }

    /** Contas do recorte de caixa: a filtrada, ou todas as ativas de dinheiro (cartão de crédito não é caixa). */
    private List<AccountDto> accountScope(Long userId, Filter f) {
        return accounts.list(userId, true).stream()
            .filter(a -> f.accountId() != null ? a.id().equals(f.accountId())
                : !a.archived() && a.type() != AccountType.CREDIT_CARD)
            .toList();
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
        return toDto(require(userId, id));
    }

    @Transactional
    public TransactionDto create(User user, TransactionRequest req) {
        Transaction t = new Transaction();
        t.setUser(user);
        apply(user.getId(), t, req);
        return toDto(transactions.save(t));
    }

    @Transactional
    public TransactionDto update(User user, Long id, TransactionRequest req, EditScope scope) {
        Transaction t = require(user.getId(), id);
        LocalDate previousScheduled = t.getScheduledDate() != null ? t.getScheduledDate() : t.getDueDate();
        apply(user.getId(), t, req);
        if (scope == EditScope.FOLLOWING && t.getRecurringBill() != null) {
            recurring.applyToFollowing(user, t, previousScheduled);
        }
        return toDto(t);
    }

    @Transactional
    public TransactionDto setPaid(Long userId, Long id, boolean paid) {
        Transaction t = require(userId, id);
        t.setPaid(paid);
        return toDto(t);
    }

    @Transactional
    public void delete(User user, Long id) {
        Transaction t = require(user.getId(), id);
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

    /**
     * Resumo como o extrato: saldo anterior das contas do recorte, transferência líquida, receitas e despesas pagas
     * no período e o saldo atual; o pendente fica à parte e entra só no saldo previsto.
     */
    private Summary summarize(int count, List<AccountDto> scope, Range range) {
        BigDecimal previous = BigDecimal.ZERO;
        BigDecimal transfer = BigDecimal.ZERO;
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        BigDecimal pendingTransfer = BigDecimal.ZERO;
        BigDecimal pendingIncome = BigDecimal.ZERO;
        BigDecimal pendingExpense = BigDecimal.ZERO;
        if (!scope.isEmpty()) {
            List<Long> ids = scope.stream().map(AccountDto::id).toList();
            previous = scope.stream().map(AccountDto::initialBalance).reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(transactions.sumPaidMovementsBefore(ids, range.from()));
            for (Object[] row : transactions.sumMovementsByTypePaidAndTransfer(ids, range.from(), range.to())) {
                boolean in = row[0] == TransactionType.INCOME;
                boolean paid = Boolean.TRUE.equals(row[1]);
                boolean isTransfer = Boolean.TRUE.equals(row[2]);
                BigDecimal amount = (BigDecimal) row[3];
                if (isTransfer) {
                    BigDecimal signed = in ? amount : amount.negate();
                    if (paid) {
                        transfer = transfer.add(signed);
                    } else {
                        pendingTransfer = pendingTransfer.add(signed);
                    }
                } else if (in) {
                    if (paid) {
                        income = income.add(amount);
                    } else {
                        pendingIncome = pendingIncome.add(amount);
                    }
                } else if (paid) {
                    expense = expense.add(amount);
                } else {
                    pendingExpense = pendingExpense.add(amount);
                }
            }
        }
        BigDecimal balance = previous.add(transfer).add(income).subtract(expense);
        BigDecimal projected = balance.add(pendingTransfer).add(pendingIncome).subtract(pendingExpense);
        return new Summary(previous, transfer, income, expense, balance, pendingTransfer, pendingIncome,
            pendingExpense, projected, count);
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
        applyInvoiceLink(userId, t, req, account);
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

    /**
     * Pagamento de fatura: despesa numa conta de dinheiro ligada a um cartão e a um mês de fatura. As compras do
     * cartão naquele período passam a ser vistas dentro dela.
     */
    private void applyInvoiceLink(Long userId, Transaction t, TransactionRequest req, Account account) {
        if (req.invoiceCardId() == null) {
            t.setCardAccount(null);
            t.setCardInvoiceMonth(null);
            return;
        }
        if (req.type() != TransactionType.EXPENSE) {
            throw new BusinessException("Pagamento de fatura precisa ser uma despesa");
        }
        if (account.isCreditCard()) {
            throw new BusinessException("O pagamento da fatura sai de uma conta de dinheiro, não do próprio cartão");
        }
        Account card = cardInvoices.requireCard(userId, req.invoiceCardId());
        YearMonth month = req.invoiceMonth() != null ? req.invoiceMonth()
            : CardInvoiceService.inferInvoiceMonth(card, req.dueDate());
        t.setCardAccount(card);
        t.setCardInvoiceMonth(month);
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
            if (f.accountId() == null) {
                // Compras de cartão só aparecem na visão do próprio cartão; na lista geral entra a fatura paga.
                p.add(cb.notEqual(root.get("account").get("type"), AccountType.CREDIT_CARD));
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
