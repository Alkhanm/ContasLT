package br.com.contaslt.recurring;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
        t.setScheduledDate(t.getDueDate());
        t.setInstallmentNumber(bill.getOccurrences() != null && t.getDueDate().equals(bill.getStartDate()) ? 1 : null);
    }

    /**
     * "Editar este e os próximos": o lançamento {@code t} já recebeu as alterações; aqui elas passam para a regra
     * e para as ocorrências pendentes previstas depois dele. Se a data mudou, a regra é reancorada nesse vencimento:
     * os próximos passam a seguir o novo dia (ex.: todo dia 1º) e são gerados de novo a partir daí.
     * Ocorrências anteriores, pagas ou não, ficam como estão.
     */
    @Transactional
    public void applyToFollowing(User user, Transaction t, LocalDate previousScheduled) {
        RecurringBill bill = t.getRecurringBill();
        if (t.getType() != bill.getType()) {
            throw new BusinessException("Para mudar o tipo, edite a conta recorrente");
        }
        bill.setDescription(t.getDescription());
        bill.setAmount(t.getAmount());
        bill.setAccount(t.getAccount());
        bill.setCategory(t.getCategory());

        LocalDate newDate = t.getDueDate();
        if (newDate.equals(previousScheduled)) {
            for (Transaction next : transactions.findByRecurringBillIdAndPaidFalseAndScheduledDateAfter(bill.getId(), previousScheduled)) {
                syncOccurrence(bill, next);
            }
            return;
        }
        if (transactions.existsByRecurringBillIdAndScheduledDateAndIdNot(bill.getId(), newDate, t.getId())) {
            throw new BusinessException("Já existe outro vencimento desta conta recorrente previsto para essa data");
        }
        Integer number = t.getInstallmentNumber() != null ? t.getInstallmentNumber()
            : RecurrenceCalculator.numberOf(bill, previousScheduled);
        transactions.deleteByRecurringBillIdAndPaidFalseAndScheduledDateAfter(bill.getId(), previousScheduled);
        if (number != null) {
            bill.setStartNumber(number);
        }
        bill.setStartDate(newDate);
        t.setScheduledDate(newDate);
        ensureOccurrences(user, YearMonth.from(newDate));
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
            Set<LocalDate> existing = transactions.findScheduledDatesByRecurringBill(bill.getId(), from, to);
            for (Occurrence occ : occurrences) {
                if (existing.contains(occ.date())) {
                    continue;
                }
                Transaction t = new Transaction();
                t.setUser(user);
                t.setRecurringBill(bill);
                t.setDueDate(occ.date());
                t.setScheduledDate(occ.date());
                t.setInstallmentNumber(bill.getOccurrences() == null ? null : occ.number());
                syncOccurrence(bill, t);
                transactions.save(t);
            }
        }
    }

    /**
     * Melhor vencimento pendente para cada candidato lido (extrato ou comprovante), com a pontuação do casamento.
     * Antes garante que os vencimentos dos meses do período existam. Cada ocorrência casa com no máximo um candidato.
     */
    @Transactional
    public <C> Map<C, ScoredOccurrence> matchPending(User user, List<C> candidates,
            java.util.function.Function<C, OccurrenceMatcher.Candidate> toCandidate, LocalDate from, LocalDate to) {
        for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(to)); m = m.plusMonths(1)) {
            ensureOccurrences(user, m);
        }
        List<Transaction> pending = transactions.findPendingOccurrences(user.getId(),
            from.minusDays(OccurrenceMatcher.MAX_DAYS), to.plusDays(OccurrenceMatcher.MAX_DAYS));
        if (pending.isEmpty()) {
            return Map.of();
        }
        Map<Long, Set<String>> keysByBill = new HashMap<>();
        for (Transaction occ : pending) {
            RecurringBill bill = occ.getRecurringBill();
            keysByBill.computeIfAbsent(bill.getId(), id -> OccurrenceMatcher.keyTokens(bill,
                transactions.findRecentPaidDescriptions(id, org.springframework.data.domain.PageRequest.of(0, 6))));
        }
        record Pair<C>(C candidate, Transaction occurrence, int score) {
        }
        List<Pair<C>> pairs = new ArrayList<>();
        for (C c : candidates) {
            OccurrenceMatcher.Candidate cand = toCandidate.apply(c);
            for (Transaction occ : pending) {
                int score = OccurrenceMatcher.score(occ, keysByBill.get(occ.getRecurringBill().getId()), cand);
                if (score > 0) {
                    pairs.add(new Pair<>(c, occ, score));
                }
            }
        }
        pairs.sort(Comparator.comparingInt((Pair<C> p) -> p.score()).reversed());
        Map<C, ScoredOccurrence> result = new HashMap<>();
        Set<Long> used = new HashSet<>();
        for (Pair<C> p : pairs) {
            if (result.containsKey(p.candidate()) || used.contains(p.occurrence().getId())) {
                continue;
            }
            result.put(p.candidate(), new ScoredOccurrence(p.occurrence(), p.score()));
            used.add(p.occurrence().getId());
        }
        return result;
    }

    public record ScoredOccurrence(Transaction occurrence, int score) {
    }

    /**
     * Um vencimento pendente passa a representar o pagamento real: valor e data vêm do extrato/comprovante,
     * a data prevista pela regra fica guardada para comparação.
     */
    public static void settle(Transaction occurrence, BigDecimal amount, LocalDate date) {
        occurrence.setAmount(amount);
        occurrence.setDueDate(date);
        occurrence.setPaid(true);
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
        if (req.matchStrictness() != null) {
            bill.setMatchStrictness(req.matchStrictness());
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
            b.isActive() ? RecurrenceCalculator.nextOnOrAfter(b, LocalDate.now()) : null, b.getMatchStrictness());
    }
}
