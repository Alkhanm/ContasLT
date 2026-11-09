package br.com.contaslt.services;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import br.com.contaslt.data.LocalStore;
import br.com.contaslt.data.schema.Entity;
import br.com.contaslt.data.schema.RecurringBillRec;
import br.com.contaslt.data.schema.TransactionRec;
import br.com.contaslt.domain.Dates;
import br.com.contaslt.domain.Ids;
import br.com.contaslt.domain.Matcher;
import br.com.contaslt.domain.Recurrence;
import br.com.contaslt.domain.Text;
import br.com.contaslt.dto.RecurringBill;
import br.com.contaslt.dto.RecurringBillInput;

/**
 * Contas recorrentes (porte de frontend/src/services/recurring.ts). Os vencimentos de cada mês viram lançamentos na
 * primeira vez que o mês é visto ({@link #ensureOccurrences}), com id determinístico por regra + data prevista: dois
 * aparelhos gerando o mesmo vencimento offline chegam ao mesmo registro.
 */
public final class RecurringService {

    /** Meses já garantidos nesta sessão; esquecidos quando uma regra muda ou chegam dados de fora. */
    private static final Set<String> ensured = new HashSet<>();
    private static final Object LOCK = new Object();

    private RecurringService() {
    }

    public static void invalidateEnsured() {
        synchronized (LOCK) {
            ensured.clear();
        }
    }

    public static List<RecurringBill> listRecurring() {
        List<RecurringBillRec> bills = new ArrayList<>(Ctx.db().list(Entity.RECURRING_BILL));
        bills.sort((a, b) -> {
            int byActive = Boolean.compare(b.active, a.active);
            return byActive != 0 ? byActive : CatalogService.COLLATOR.compare(a.description, b.description);
        });
        List<RecurringBill> out = new ArrayList<>();
        for (RecurringBillRec b : bills) {
            out.add(Mappers.recurringDto(b));
        }
        return out;
    }

    public static RecurringBillRec requireBill(String id) {
        return Ctx.require(Ctx.db().get(Entity.RECURRING_BILL, id), "Conta recorrente não encontrada");
    }

    /** Vencimento que existiu e foi excluído (há só a marcação de exclusão). */
    private static boolean isDeletedOccurrence(LocalStore s, String id) {
        return s.get(Entity.TRANSACTION, id) == null && s.known(Entity.TRANSACTION, id);
    }

    private static void check(RecurringBillInput req) {
        String description = req.description == null ? "" : Text.trim(req.description);
        Ctx.validate()
            .check("description", !description.isEmpty(), "Informe a descrição")
            .check("description", description.length() <= 140, "Descrição muito longa")
            .check("amount", req.amount != null, "Informe o valor")
            .check("amount", req.amount == null || req.amount > 0, "O valor deve ser maior que zero")
            .check("type", Text.present(req.type), "Informe o tipo")
            .check("frequency", Text.present(req.frequency), "Informe a frequência")
            .check("startDate", Text.present(req.startDate), "Informe a data do primeiro vencimento")
            .check("occurrences", req.occurrences == null || req.occurrences >= 2,
                "Parcelamento precisa de pelo menos 2 parcelas")
            .check("occurrences", req.occurrences == null || req.occurrences <= 600, "Máximo de 600 parcelas")
            .check("accountId", req.accountId != null, "Escolha a conta")
            .check("notes", (req.notes == null ? "" : req.notes).length() <= 500, "Observação muito longa")
            .check("matchStrictness", req.matchStrictness == null
                || (req.matchStrictness >= 1 && req.matchStrictness <= 10), "Precisão entre 1 e 10")
            .throwIfAny();
        if (Text.present(req.endDate) && req.endDate.compareTo(req.startDate) < 0) {
            throw new Ctx.BusinessException("A data final não pode ser anterior ao primeiro vencimento");
        }
    }

    private static void applyBill(RecurringBillRec bill, RecurringBillInput req) {
        Ctx.require(Ctx.db().get(Entity.ACCOUNT, req.accountId), "Conta não encontrada");
        var category = CatalogService.requireCategoryOrNull(req.categoryId);
        if (category != null && !category.type.equals(req.type)) {
            throw new Ctx.BusinessException("A categoria escolhida não é do mesmo tipo do lançamento");
        }
        bill.description = Text.trim(req.description);
        bill.amount = req.amount;
        bill.type = req.type;
        bill.frequency = req.frequency;
        bill.startDate = req.startDate;
        bill.endDate = req.endDate;
        bill.occurrences = req.occurrences;
        bill.accountId = req.accountId;
        bill.categoryId = category == null ? null : category.id;
        bill.notes = req.notes;
        if (req.active != null) {
            bill.active = req.active;
        }
        if (req.matchStrictness != null) {
            bill.matchStrictness = req.matchStrictness;
        }
    }

    public static RecurringBill createRecurring(RecurringBillInput req) {
        invalidateEnsured();
        check(req);
        RecurringBillRec bill = new RecurringBillRec();
        bill.id = Ids.newId();
        bill.accountId = null;
        bill.categoryId = null;
        bill.description = "";
        bill.amount = 0;
        bill.type = "EXPENSE";
        bill.frequency = "MONTHLY";
        bill.startDate = req.startDate;
        bill.endDate = null;
        bill.occurrences = null;
        bill.startNumber = 1;
        bill.matchStrictness = 5;
        bill.notes = null;
        bill.active = true;
        bill.createdAt = Dates.nowIso();
        applyBill(bill, req);
        Ctx.db().put(Entity.RECURRING_BILL, bill);
        if (req.fromTransactionId != null) {
            adopt(bill, req.fromTransactionId);
        }
        ensureOccurrences(Dates.currentMonth());
        return Mappers.recurringDto(bill);
    }

    /**
     * Lançamento existente vira ocorrência da regra: mantém data, valor e situação de pago; só passa a contar como
     * vencimento dela, então não é gerado outro na mesma data.
     */
    private static void adopt(RecurringBillRec bill, String transactionId) {
        TransactionRec t = Ctx.require(Ctx.db().get(Entity.TRANSACTION, transactionId), "Lançamento não encontrado");
        if (Text.present(t.recurringBillId)) {
            throw new Ctx.BusinessException("Este lançamento já pertence a uma conta recorrente");
        }
        if (Text.present(t.transferId)) {
            throw new Ctx.BusinessException("Transferência entre contas não vira conta recorrente");
        }
        if (!Objects.equals(t.type, bill.type)) {
            throw new Ctx.BusinessException("O tipo da recorrência precisa ser o mesmo do lançamento");
        }
        TransactionRec next = t.copy();
        next.recurringBillId = bill.id;
        next.scheduledDate = t.dueDate;
        next.installmentNumber = bill.occurrences != null && t.dueDate.equals(bill.startDate) ? Integer.valueOf(1) : null;
        Ctx.db().put(Entity.TRANSACTION, next);
    }

    private static List<TransactionRec> occurrencesOf(String billId) {
        List<TransactionRec> out = new ArrayList<>();
        for (TransactionRec t : Ctx.db().list(Entity.TRANSACTION)) {
            if (billId.equals(t.recurringBillId)) {
                out.add(t);
            }
        }
        return out;
    }

    private static TransactionRec syncOccurrence(RecurringBillRec bill, TransactionRec t) {
        TransactionRec out = t.copy();
        out.description = bill.description;
        out.amount = bill.amount;
        out.type = bill.type;
        out.accountId = bill.accountId != null ? bill.accountId : t.accountId;
        out.categoryId = bill.categoryId;
        return out;
    }

    private static boolean sameOccurrence(TransactionRec a, TransactionRec b) {
        return Objects.equals(a.description, b.description) && a.amount == b.amount && Objects.equals(a.type, b.type)
            && Objects.equals(a.accountId, b.accountId) && Objects.equals(a.categoryId, b.categoryId);
    }

    /**
     * "Editar este e os próximos": {@code t} já recebeu as alterações; aqui elas passam para a regra e para as
     * ocorrências pendentes previstas depois dele. Se a data mudou, a regra é reancorada nesse vencimento e os próximos
     * são gerados de novo a partir daí. Ocorrências anteriores ficam como estão. Devolve o lançamento como ficou.
     */
    public static TransactionRec applyToFollowing(TransactionRec t, String previousScheduled) {
        invalidateEnsured();
        LocalStore s = Ctx.db();
        RecurringBillRec bill = requireBill(t.recurringBillId).copy();
        if (!Objects.equals(t.type, bill.type)) {
            throw new Ctx.BusinessException("Para mudar o tipo, edite a conta recorrente");
        }
        bill.description = t.description;
        bill.amount = t.amount;
        bill.accountId = t.accountId;
        bill.categoryId = t.categoryId;

        String newDate = t.dueDate;
        if (newDate.equals(previousScheduled)) {
            s.put(Entity.RECURRING_BILL, bill);
            for (TransactionRec next : occurrencesOf(bill.id)) {
                if (next.paid || !Text.present(next.scheduledDate) || next.scheduledDate.compareTo(previousScheduled) <= 0
                    || next.id.equals(t.id)) {
                    continue;
                }
                TransactionRec synced = syncOccurrence(bill, next);
                if (!sameOccurrence(synced, next)) {
                    s.put(Entity.TRANSACTION, synced);
                }
            }
            return t;
        }
        for (TransactionRec o : occurrencesOf(bill.id)) {
            if (newDate.equals(o.scheduledDate) && !o.id.equals(t.id)) {
                throw new Ctx.BusinessException("Já existe outro vencimento desta conta recorrente previsto para essa data");
            }
        }
        Integer number = t.installmentNumber != null ? t.installmentNumber : Recurrence.numberOf(bill, previousScheduled);
        for (TransactionRec o : occurrencesOf(bill.id)) {
            if (!o.paid && Text.present(o.scheduledDate) && o.scheduledDate.compareTo(previousScheduled) > 0
                && !o.id.equals(t.id)) {
                s.remove(Entity.TRANSACTION, o.id);
            }
        }
        if (number != null) {
            bill.startNumber = number;
        }
        bill.startDate = newDate;
        s.put(Entity.RECURRING_BILL, bill);
        TransactionRec moved = t.copy();
        moved.scheduledDate = newDate;
        s.put(Entity.TRANSACTION, moved);
        ensureOccurrences(Dates.monthOf(newDate));
        return moved;
    }

    public static RecurringBill updateRecurring(String id, RecurringBillInput req) {
        invalidateEnsured();
        check(req);
        LocalStore s = Ctx.db();
        RecurringBillRec bill = requireBill(id).copy();
        boolean scheduleChanged = !Objects.equals(bill.frequency, req.frequency)
            || !Objects.equals(bill.startDate, req.startDate) || !Objects.equals(bill.endDate, req.endDate)
            || !Objects.equals(bill.occurrences, req.occurrences);
        applyBill(bill, req);
        s.put(Entity.RECURRING_BILL, bill);
        for (TransactionRec t : occurrencesOf(id)) {
            if (t.paid) {
                continue;
            }
            if (scheduleChanged || !bill.active) {
                // Pendentes são derivados da regra: recriados conforme o novo calendário (ou some, se a regra parou).
                s.remove(Entity.TRANSACTION, t.id);
            } else {
                TransactionRec synced = syncOccurrence(bill, t);
                if (!sameOccurrence(synced, t)) {
                    s.put(Entity.TRANSACTION, synced);
                }
            }
        }
        ensureOccurrences(Dates.currentMonth());
        return Mappers.recurringDto(bill);
    }

    public static void deleteRecurring(String id) {
        invalidateEnsured();
        LocalStore s = Ctx.db();
        requireBill(id);
        for (TransactionRec t : occurrencesOf(id)) {
            if (!t.paid) {
                s.remove(Entity.TRANSACTION, t.id);
            } else {
                TransactionRec next = t.copy();
                next.recurringBillId = null;
                s.put(Entity.TRANSACTION, next);
            }
        }
        s.remove(Entity.RECURRING_BILL, id);
    }

    /**
     * Garante que todos os vencimentos das regras ativas no mês existam como lançamentos. Idempotente: a chave é
     * (regra, data prevista), e o id do vencimento é derivado dela. Só gera depois da primeira sincronização completa
     * do aparelho ({@link Ctx#canGenerate()}).
     */
    public static void ensureOccurrences(String month) {
        synchronized (LOCK) {
            if (ensured.contains(month) || !Ctx.canGenerate()) {
                return;
            }
            LocalStore s = Ctx.db();
            List<RecurringBillRec> active = new ArrayList<>();
            for (RecurringBillRec b : s.list(Entity.RECURRING_BILL)) {
                if (b.active && Text.present(b.accountId) && s.get(Entity.ACCOUNT, b.accountId) != null) {
                    active.add(b);
                }
            }
            if (!active.isEmpty()) {
                String from = Dates.firstDay(month);
                String to = Dates.endOfMonth(month);
                Set<String> existing = new HashSet<>();
                for (TransactionRec t : s.list(Entity.TRANSACTION)) {
                    if (Text.present(t.recurringBillId) && Text.present(t.scheduledDate)
                        && t.scheduledDate.compareTo(from) >= 0 && t.scheduledDate.compareTo(to) <= 0) {
                        existing.add(t.recurringBillId + "|" + t.scheduledDate);
                    }
                }
                for (RecurringBillRec bill : active) {
                    for (Recurrence.Occurrence occ : Recurrence.between(bill, from, to)) {
                        if (existing.contains(bill.id + "|" + occ.date())) {
                            continue;
                        }
                        String id = Ids.occurrenceId(bill.id, occ.date());
                        // Vencimento que o usuário excluiu (marcação de exclusão): não volta.
                        if (isDeletedOccurrence(s, id)) {
                            continue;
                        }
                        // O id desta data já é de um vencimento que foi movido (reancorado) para outra data prevista: usa
                        // um id alternativo, também determinístico, para os outros aparelhos chegarem ao mesmo registro.
                        TransactionRec taken = s.get(Entity.TRANSACTION, id);
                        if (taken != null) {
                            if (bill.id.equals(taken.recurringBillId) && occ.date().equals(taken.scheduledDate)) {
                                continue;
                            }
                            id = Ids.occurrenceId(bill.id, occ.date() + "#2");
                            if (s.get(Entity.TRANSACTION, id) != null || isDeletedOccurrence(s, id)) {
                                continue;
                            }
                        }
                        TransactionRec t = new TransactionRec();
                        t.id = id;
                        t.accountId = bill.accountId;
                        t.recurringBillId = bill.id;
                        t.description = "";
                        t.amount = 0;
                        t.type = bill.type;
                        t.dueDate = occ.date();
                        t.scheduledDate = occ.date();
                        t.paid = false;
                        t.installmentNumber = bill.occurrences == null ? null : Integer.valueOf(occ.number());
                        t.linkedImport = false;
                        t.createdAt = Dates.nowIso();
                        s.put(Entity.TRANSACTION, syncOccurrence(bill, t), true);
                    }
                }
            }
            ensured.add(month);
        }
    }

    /** Garante os vencimentos de todos os meses que tocam o intervalo. */
    public static void ensureRange(String from, String to) {
        for (String m : Dates.monthsBetween(Dates.monthOf(from), Dates.monthOf(to))) {
            ensureOccurrences(m);
        }
    }

    public static final class ScoredOccurrence {
        public final TransactionRec occurrence;
        public final int score;

        public ScoredOccurrence(TransactionRec occurrence, int score) {
            this.occurrence = occurrence;
            this.score = score;
        }
    }

    /**
     * Melhor vencimento pendente para cada candidato lido (extrato ou comprovante). Antes garante os vencimentos dos
     * meses do período. Cada ocorrência casa com no máximo um candidato.
     *
     * <p>Como o Map do JavaScript, o resultado usa a identidade do objeto candidato como chave (duas linhas iguais do
     * extrato são candidatos diferentes) e itera na ordem de inserção (maior pontuação primeiro).
     */
    public static <C> Map<C, ScoredOccurrence> matchPending(List<C> candidates, Function<C, Matcher.Candidate> toCandidate,
                                                            String from, String to) {
        ensureRange(from, to);
        LocalStore s = Ctx.db();
        String lo = Dates.plusDays(from, -Matcher.MAX_DAYS);
        String hi = Dates.plusDays(to, Matcher.MAX_DAYS);
        List<TransactionRec> txs = s.list(Entity.TRANSACTION);
        List<TransactionRec> pending = new ArrayList<>();
        for (TransactionRec t : txs) {
            if (!Text.present(t.recurringBillId) || t.paid || Text.present(t.importBatchId) || Text.present(t.externalId)
                || t.dueDate.compareTo(lo) < 0 || t.dueDate.compareTo(hi) > 0) {
                continue;
            }
            RecurringBillRec b = s.get(Entity.RECURRING_BILL, t.recurringBillId);
            if (b != null && b.active) {
                pending.add(t);
            }
        }
        IdentityLinkedMap<C, ScoredOccurrence> result = new IdentityLinkedMap<>();
        if (pending.isEmpty()) {
            return result;
        }

        Map<String, Set<String>> keysByBill = new HashMap<>();
        for (TransactionRec occ : pending) {
            String billId = occ.recurringBillId;
            if (keysByBill.containsKey(billId)) {
                continue;
            }
            RecurringBillRec bill = s.get(Entity.RECURRING_BILL, billId);
            List<TransactionRec> paid = new ArrayList<>();
            for (TransactionRec t : txs) {
                if (billId.equals(t.recurringBillId) && t.paid) {
                    paid.add(t);
                }
            }
            paid.sort((a, b) -> b.dueDate.compareTo(a.dueDate));
            List<String> history = new ArrayList<>();
            for (int i = 0; i < Math.min(6, paid.size()); i++) {
                TransactionRec t = paid.get(i);
                history.add((t.merchant == null ? "" : t.merchant) + " " + t.description);
            }
            keysByBill.put(billId, Matcher.keyTokens(bill, history));
        }

        final class Pair {
            final C c;
            final TransactionRec occ;
            final int score;

            Pair(C c, TransactionRec occ, int score) {
                this.c = c;
                this.occ = occ;
                this.score = score;
            }
        }
        List<Pair> pairs = new ArrayList<>();
        for (C c : candidates) {
            Matcher.Candidate cand = toCandidate.apply(c);
            for (TransactionRec occ : pending) {
                int sc = Matcher.score(occ, s.get(Entity.RECURRING_BILL, occ.recurringBillId),
                    keysByBill.get(occ.recurringBillId), cand);
                if (sc > 0) {
                    pairs.add(new Pair(c, occ, sc));
                }
            }
        }
        pairs.sort((a, b) -> Integer.compare(b.score, a.score));
        Set<String> used = new HashSet<>();
        for (Pair p : pairs) {
            if (result.containsKey(p.c) || used.contains(p.occ.id)) {
                continue;
            }
            result.put(p.c, new ScoredOccurrence(p.occ, p.score));
            used.add(p.occ.id);
        }
        return result;
    }

    /** O vencimento passa a representar o pagamento real: valor e data do extrato/comprovante, data prevista guardada. */
    public static TransactionRec settle(TransactionRec occurrence, long amount, String date) {
        TransactionRec t = occurrence.copy();
        t.amount = amount;
        t.dueDate = date;
        t.paid = true;
        return t;
    }

    /** Map por identidade da chave que mantém a ordem de inserção (como o Map do JavaScript com objetos). */
    private static final class IdentityLinkedMap<K, V> extends AbstractMap<K, V> {
        private final IdentityHashMap<K, V> index = new IdentityHashMap<>();
        private final List<Map.Entry<K, V>> entries = new ArrayList<>();

        @Override
        public V get(Object key) {
            return index.get(key);
        }

        @Override
        public boolean containsKey(Object key) {
            return index.containsKey(key);
        }

        @Override
        public V put(K key, V value) {
            if (index.containsKey(key)) {
                for (Map.Entry<K, V> e : entries) {
                    if (e.getKey() == key) {
                        e.setValue(value);
                    }
                }
            } else {
                entries.add(new SimpleEntry<>(key, value));
            }
            return index.put(key, value);
        }

        @Override
        public Set<Map.Entry<K, V>> entrySet() {
            return new AbstractSet<>() {
                @Override
                public Iterator<Map.Entry<K, V>> iterator() {
                    Iterator<Map.Entry<K, V>> it = entries.iterator();
                    return new Iterator<>() {
                        Map.Entry<K, V> last;

                        @Override
                        public boolean hasNext() {
                            return it.hasNext();
                        }

                        @Override
                        public Map.Entry<K, V> next() {
                            last = it.next();
                            return last;
                        }

                        @Override
                        public void remove() {
                            it.remove();
                            index.remove(last.getKey());
                        }
                    };
                }

                @Override
                public int size() {
                    return entries.size();
                }
            };
        }
    }
}
