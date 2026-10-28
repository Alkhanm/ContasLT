package br.com.contaslt.recurring;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.contaslt.account.Account;
import br.com.contaslt.account.AccountService;
import br.com.contaslt.category.Category;
import br.com.contaslt.category.CategoryService;
import br.com.contaslt.common.BusinessException;
import br.com.contaslt.common.NotFoundException;
import br.com.contaslt.recurring.RecurrenceCalculator.Occurrence;
import br.com.contaslt.recurring.RecurringBillDtos.RecurringBillDto;
import br.com.contaslt.recurring.RecurringBillDtos.RecurringBillRequest;
import br.com.contaslt.transaction.Transaction;
import br.com.contaslt.transaction.TransactionRepository;
import br.com.contaslt.user.User;

@Service
public class RecurringBillService {

    private final RecurringBillRepository bills;
    private final TransactionRepository transactions;
    private final AccountService accounts;
    private final CategoryService categories;

    public RecurringBillService(RecurringBillRepository bills, TransactionRepository transactions,
            AccountService accounts, CategoryService categories) {
        this.bills = bills;
        this.transactions = transactions;
        this.accounts = accounts;
        this.categories = categories;
    }

    @Transactional(readOnly = true)
    public List<RecurringBillDto> list(Long userId) {
        return bills.findByUserIdOrderByActiveDescDescriptionAsc(userId).stream().map(this::toDto).toList();
    }

    @Transactional
    public RecurringBillDto create(User user, RecurringBillRequest req) {
        validate(req);
        RecurringBill bill = new RecurringBill();
        bill.setUser(user);
        apply(user.getId(), bill, req);
        bill = bills.save(bill);
        if (req.fromTransactionId() != null) {
            adopt(user, bill, req.fromTransactionId());
        }
        ensureOccurrences(user, YearMonth.now());
        return toDto(bill);
    }

    /**
     * Vincula um lançamento já existente à regra como ocorrência dela. O lançamento mantém data, valor e
     * situação de pago; só passa a contar como vencimento da regra, então não é gerado outro na mesma data.
     */
    private void adopt(User user, RecurringBill bill, Long transactionId) {
        Transaction t = transactions.findByIdAndUserId(transactionId, user.getId())
            .orElseThrow(() -> new NotFoundException("Lançamento não encontrado"));
        if (t.getRecurringBill() != null) {
            throw new BusinessException("Este lançamento já pertence a uma conta recorrente");
        }
        if (t.isTransfer()) {
            throw new BusinessException("Transferência entre contas não vira conta recorrente");
        }
        if (t.getType() != bill.getType()) {
            throw new BusinessException("O tipo da recorrência precisa ser o mesmo do lançamento");
        }
        t.setRecurringBill(bill);
        t.setInstallmentNumber(bill.getOccurrences() != null && t.getDueDate().equals(bill.getStartDate()) ? 1 : null);
    }

    @Transactional
    public RecurringBillDto update(User user, Long id, RecurringBillRequest req) {
        validate(req);
        RecurringBill bill = require(user.getId(), id);
        boolean scheduleChanged = !Objects.equals(bill.getFrequency(), req.frequency())
            || !Objects.equals(bill.getStartDate(), req.startDate())
            || !Objects.equals(bill.getEndDate(), req.endDate())
            || !Objects.equals(bill.getOccurrences(), req.occurrences());
        apply(user.getId(), bill, req);

        if (scheduleChanged) {
            // Lançamentos pendentes são derivados da regra: recria-os conforme o novo calendário.
            transactions.deleteByRecurringBillIdAndPaidFalse(bill.getId());
        } else {
            for (Transaction t : transactions.findByRecurringBillIdAndPaidFalse(bill.getId())) {
                syncOccurrence(bill, t);
            }
        }
        if (!bill.isActive()) {
            transactions.deleteByRecurringBillIdAndPaidFalse(bill.getId());
        }
        ensureOccurrences(user, YearMonth.now());
        return toDto(bill);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        RecurringBill bill = require(userId, id);
        transactions.deleteByRecurringBillIdAndPaidFalse(bill.getId());
        transactions.detachFromRecurringBill(bill.getId());
        bills.delete(bill);
    }

    /**
     * Garante que todos os vencimentos das contas recorrentes ativas do mês existam como lançamentos.
     * Idempotente: usa o índice único (recurring_bill_id, due_date).
     */
    @Transactional
    public void ensureOccurrences(User user, YearMonth month) {
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();
        for (RecurringBill bill : bills.findByUserIdAndActiveTrue(user.getId())) {
            List<Occurrence> occurrences = RecurrenceCalculator.between(bill, from, to);
            if (occurrences.isEmpty()) {
                continue;
            }
            Set<LocalDate> existing = transactions.findDueDatesByRecurringBill(bill.getId(), from, to);
            for (Occurrence occ : occurrences) {
                if (existing.contains(occ.date())) {
                    continue;
                }
                Transaction t = new Transaction();
                t.setUser(user);
                t.setRecurringBill(bill);
                t.setDueDate(occ.date());
                t.setInstallmentNumber(bill.getOccurrences() == null ? null : occ.number());
                syncOccurrence(bill, t);
                transactions.save(t);
            }
        }
    }

    public RecurringBill require(Long userId, Long id) {
        return bills.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new NotFoundException("Conta recorrente não encontrada"));
    }

    private static void syncOccurrence(RecurringBill bill, Transaction t) {
        t.setDescription(bill.getDescription());
        t.setAmount(bill.getAmount());
        t.setType(bill.getType());
        t.setAccount(bill.getAccount());
        t.setCategory(bill.getCategory());
    }

    private void apply(Long userId, RecurringBill bill, RecurringBillRequest req) {
        Account account = accounts.require(userId, req.accountId());
        Category category = categories.requireOrNull(userId, req.categoryId());
        if (category != null && category.getType() != req.type()) {
            throw new BusinessException("A categoria escolhida não é do mesmo tipo do lançamento");
        }
        bill.setDescription(req.description().trim());
        bill.setAmount(req.amount());
        bill.setType(req.type());
        bill.setFrequency(req.frequency());
        bill.setStartDate(req.startDate());
        bill.setEndDate(req.endDate());
        bill.setOccurrences(req.occurrences());
        bill.setAccount(account);
        bill.setCategory(category);
        bill.setNotes(req.notes());
        if (req.active() != null) {
            bill.setActive(req.active());
        }
    }

    private static void validate(RecurringBillRequest req) {
        if (req.endDate() != null && req.endDate().isBefore(req.startDate())) {
            throw new BusinessException("A data final não pode ser anterior ao primeiro vencimento");
        }
    }

    private RecurringBillDto toDto(RecurringBill b) {
        Account a = b.getAccount();
        Category c = b.getCategory();
        return new RecurringBillDto(b.getId(), b.getDescription(), b.getAmount(), b.getType(), b.getFrequency(),
            b.getStartDate(), b.getEndDate(), b.getOccurrences(),
            a == null ? null : a.getId(), a == null ? null : a.getName(),
            c == null ? null : c.getId(), c == null ? null : c.getName(), c == null ? null : c.getColor(),
            c == null ? null : c.getIcon(), b.getNotes(), b.isActive(),
            b.isActive() ? RecurrenceCalculator.nextOnOrAfter(b, LocalDate.now()) : null);
    }
}
