package br.com.contaslt.services;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import br.com.contaslt.data.LocalStore;
import br.com.contaslt.data.schema.AccountRec;
import br.com.contaslt.data.schema.Entity;
import br.com.contaslt.data.schema.RecurringBillRec;
import br.com.contaslt.data.schema.TransactionRec;
import br.com.contaslt.data.schema.TransferRec;
import br.com.contaslt.data.schema.TransferRejectionRec;
import br.com.contaslt.domain.Dates;
import br.com.contaslt.domain.Ids;
import br.com.contaslt.domain.Text;
import br.com.contaslt.dto.Transfer;
import br.com.contaslt.dto.TransferInput;
import br.com.contaslt.dto.TransferSuggestion;
import br.com.contaslt.dto.TransferSuggestionList;

/**
 * Transferências entre contas do mesmo usuário (porte de frontend/src/services/transfers.ts). Cada transferência é um
 * par de lançamentos (despesa na origem, receita no destino) vinculados por um registro {@code transfer} e pelo
 * {@code transferId} nas duas pernas; eles continuam valendo para o saldo de cada conta, mas são ignorados em receitas,
 * despesas e categorias, por serem só movimentação interna. Cartão de crédito fica de fora: o pagamento da fatura é
 * uma despesa comum ligada ao cartão.
 */
public final class TransfersService {

    /** Janela máxima entre a saída e a entrada para o cruzamento automático sugerir o par. */
    public static final int MAX_DAYS_APART = 3;
    /** Quanto do histórico é vasculhado em busca de pares. */
    private static final int LOOKBACK_MONTHS = 18;
    private static final List<String> TRANSFER_WORDS = Arrays.asList(
        "transf", "pix", "ted", "doc ", "aplicacao", "resgate", "envio", "enviad", "recebid", "entre contas");

    private TransfersService() {
    }

    // ---------- Recusas ("não é transferência") ----------

    /** Ocorrência de conta recorrente vira {@code bill:<id>} (a recusa cobre todos os meses); avulso, {@code tx:<id>}. */
    private static String keyOf(TransactionRec t) {
        RecurringBillRec bill = Ctx.db().get(Entity.RECURRING_BILL, t.recurringBillId);
        return bill != null ? "bill:" + bill.id : "tx:" + t.id;
    }

    /** Chave do par, independente da ordem. */
    private static String pairKey(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    /**
     * "Não é transferência": o par some das sugestões. Se os dois lançamentos são ocorrências de contas recorrentes, a
     * recusa vale para aquelas duas regras em todos os meses. Devolve se a recusa cobre as regras recorrentes.
     */
    public static boolean rejectTransfer(String transactionId, String otherTransactionId) {
        TransactionRec a = TransactionsService.requireTransaction(transactionId);
        TransactionRec b = TransactionsService.requireTransaction(otherTransactionId);
        if (a.id.equals(b.id)) {
            throw new Ctx.BusinessException("Escolha dois lançamentos diferentes");
        }
        String ka = keyOf(a);
        String kb = keyOf(b);
        String aKey = ka.compareTo(kb) <= 0 ? ka : kb;
        String bKey = ka.compareTo(kb) <= 0 ? kb : ka;
        LocalStore s = Ctx.db();
        boolean exists = false;
        for (TransferRejectionRec r : s.list(Entity.TRANSFER_REJECTION)) {
            if (r.aKey.equals(aKey) && r.bKey.equals(bKey)) {
                exists = true;
                break;
            }
        }
        if (!exists) {
            TransferRejectionRec r = new TransferRejectionRec();
            r.id = Ids.newId();
            r.aKey = aKey;
            r.bKey = bKey;
            r.createdAt = Dates.nowIso();
            s.put(Entity.TRANSFER_REJECTION, r);
        }
        return aKey.startsWith("bill:") && bKey.startsWith("bill:");
    }

    /** Esquece todas as recusas: os pares voltam a ser sugeridos. */
    public static void forgetRejections() {
        LocalStore s = Ctx.db();
        for (TransferRejectionRec r : new ArrayList<>(s.list(Entity.TRANSFER_REJECTION))) {
            s.remove(Entity.TRANSFER_REJECTION, r.id);
        }
    }

    // ---------- Criação, vínculo e desvínculo ----------

    private static AccountRec requireAccount(String id) {
        return Ctx.require(Ctx.db().get(Entity.ACCOUNT, id), "Conta não encontrada");
    }

    public static Transfer createTransfer(TransferInput req) {
        Ctx.validate()
            .check("fromAccountId", req.fromAccountId != null, "Escolha a conta de origem")
            .check("toAccountId", req.toAccountId != null, "Escolha a conta de destino")
            .check("amount", req.amount != null, "Informe o valor")
            .check("amount", req.amount == null || req.amount > 0, "O valor deve ser maior que zero")
            .check("date", Text.present(req.date), "Informe a data")
            .check("description", (req.description == null ? 0 : req.description.length()) <= 140, "Descrição muito longa")
            .throwIfAny();
        if (req.fromAccountId.equals(req.toAccountId)) {
            throw new Ctx.BusinessException("Origem e destino precisam ser contas diferentes");
        }
        AccountRec from = requireAccount(req.fromAccountId);
        AccountRec to = requireAccount(req.toAccountId);
        if (CardsService.isCreditCard(from) || CardsService.isCreditCard(to)) {
            throw new Ctx.BusinessException("Cartão de crédito não entra em transferência: registre a fatura como despesa "
                + "ligada ao cartão (pagamento de fatura)");
        }
        String description = req.description == null || Text.trim(req.description).isEmpty()
            ? "Transferência" : Text.trim(req.description);
        long amount = req.amount;

        // Sem "paid" informado, a transferência conta como feita (como no PWA).
        boolean paid = req.paid == null || req.paid;
        TransactionRec out = leg(from, "EXPENSE", amount, req.date, description, paid);
        TransactionRec into = leg(to, "INCOME", amount, req.date, description, paid);
        return toDto(link(out, into));
    }

    /** Vincula dois lançamentos existentes; a ordem informada não importa, a despesa vira a origem. */
    public static Transfer linkTransfer(String transactionId, String otherTransactionId) {
        TransactionRec a = TransactionsService.requireTransaction(transactionId);
        TransactionRec b = TransactionsService.requireTransaction(otherTransactionId);
        if (a.id.equals(b.id)) {
            throw new Ctx.BusinessException("Escolha dois lançamentos diferentes");
        }
        if (a.type.equals(b.type)) {
            throw new Ctx.BusinessException("Uma transferência precisa de uma saída (despesa) e uma entrada (receita)");
        }
        TransactionRec out = "EXPENSE".equals(a.type) ? a : b;
        TransactionRec into = out == a ? b : a;
        validatePair(out, into);
        return toDto(link(out, into));
    }

    /** Desfaz o vínculo; os dois lançamentos continuam existindo como despesa e receita comuns. */
    public static void unlinkTransfer(String id) {
        LocalStore s = Ctx.db();
        TransferRec t = Ctx.require(s.get(Entity.TRANSFER, id), "Transferência não encontrada");
        for (String legId : new String[] {t.fromTransactionId, t.toTransactionId}) {
            TransactionRec leg = s.get(Entity.TRANSACTION, legId);
            if (leg != null && t.id.equals(leg.transferId)) {
                TransactionRec next = leg.copy();
                next.transferId = null;
                s.put(Entity.TRANSACTION, next);
            }
        }
        s.remove(Entity.TRANSFER, t.id);
    }

    private static void validatePair(TransactionRec out, TransactionRec into) {
        LocalStore s = Ctx.db();
        AccountRec outAccount = s.get(Entity.ACCOUNT, out.accountId);
        AccountRec inAccount = s.get(Entity.ACCOUNT, into.accountId);
        if (CardsService.isCreditCard(outAccount) || CardsService.isCreditCard(inAccount)) {
            throw new Ctx.BusinessException("Compras de cartão não entram em transferência; a fatura é uma despesa ligada ao cartão");
        }
        if (Text.present(out.transferId) || Text.present(into.transferId)) {
            throw new Ctx.BusinessException("Um dos lançamentos já faz parte de outra transferência");
        }
        if (out.accountId.equals(into.accountId)) {
            throw new Ctx.BusinessException("A saída e a entrada precisam estar em contas diferentes");
        }
        if (out.amount != into.amount) {
            throw new Ctx.BusinessException("Os dois lançamentos precisam ter o mesmo valor");
        }
    }

    private static TransferRec link(TransactionRec out, TransactionRec into) {
        LocalStore s = Ctx.db();
        TransferRec t = new TransferRec();
        t.id = Ids.newId();
        t.fromTransactionId = out.id;
        t.toTransactionId = into.id;
        t.createdAt = Dates.nowIso();
        s.put(Entity.TRANSFER, t);
        // Transferência não é despesa nem receita: perde a categoria para não poluir os relatórios.
        for (TransactionRec leg : new TransactionRec[] {out, into}) {
            TransactionRec next = leg.copy();
            next.transferId = t.id;
            next.categoryId = null;
            s.put(Entity.TRANSACTION, next);
        }
        return t;
    }

    private static TransactionRec leg(AccountRec account, String type, long amount, String date, String description,
                                      boolean paid) {
        TransactionRec t = TransactionsService.emptyTransaction();
        t.accountId = account.id;
        t.type = type;
        t.amount = amount;
        t.dueDate = date;
        t.description = description;
        t.paid = paid;
        Ctx.db().put(Entity.TRANSACTION, t);
        return t;
    }

    private static Transfer toDto(TransferRec t) {
        LocalStore s = Ctx.db();
        Transfer d = new Transfer();
        d.id = t.id;
        d.from = Mappers.transactionDto(s.get(Entity.TRANSACTION, t.fromTransactionId));
        d.to = Mappers.transactionDto(s.get(Entity.TRANSACTION, t.toTransactionId));
        return d;
    }

    // ---------- Cruzamento automático ----------

    private static int cmp(String a, String b) {
        return Integer.signum(a.compareTo(b));
    }

    /** Ordem de criação (o id sequencial do backend): createdAt e, no empate, o id. */
    private static int byCreation(TransactionRec a, TransactionRec b) {
        int c = cmp(a.createdAt, b.createdAt);
        return c != 0 ? c : cmp(a.id, b.id);
    }

    /** Lançamentos sem vínculo de transferência desde {@code from}, fora de cartão, por data e criação. */
    private static List<TransactionRec> unlinkedSince(String from) {
        LocalStore s = Ctx.db();
        List<TransactionRec> out = new ArrayList<>();
        for (TransactionRec t : s.list(Entity.TRANSACTION)) {
            if (Text.present(t.transferId) || t.dueDate.compareTo(from) < 0) {
                continue;
            }
            AccountRec a = s.get(Entity.ACCOUNT, t.accountId);
            if (a != null && !CardsService.isCreditCard(a)) {
                out.add(t);
            }
        }
        out.sort((a, b) -> {
            int c = cmp(a.dueDate, b.dueDate);
            return c != 0 ? c : byCreation(a, b);
        });
        return out;
    }

    private static final class Candidate {
        final TransactionRec out;
        final TransactionRec into;
        final int days;
        final int score;

        Candidate(TransactionRec out, TransactionRec into, int days, int score) {
            this.out = out;
            this.into = into;
            this.days = days;
            this.score = score;
        }
    }

    /**
     * Cruzamento automático: procura despesas e receitas de mesmo valor, em contas diferentes, com até
     * {@link #MAX_DAYS_APART} dias de distância. Cada lançamento entra em no máximo um par; em caso de empate, vence o
     * par com datas mais próximas e descrição mais parecida com uma transferência. {@code userName}: nome do usuário
     * logado (descrições que o citam ganham pontos), que vem da sessão.
     */
    public static TransferSuggestionList transferSuggestions(String userName) {
        LocalStore s = Ctx.db();
        List<TransactionRec> pool = unlinkedSince(Dates.plusMonths(Dates.today(), -LOOKBACK_MONTHS));
        List<TransactionRec> outs = new ArrayList<>();
        List<TransactionRec> ins = new ArrayList<>();
        for (TransactionRec t : pool) {
            if ("EXPENSE".equals(t.type)) {
                outs.add(t);
            } else if ("INCOME".equals(t.type)) {
                ins.add(t);
            }
        }
        String name = Text.normalize(userName);
        List<TransferRejectionRec> rejections = s.list(Entity.TRANSFER_REJECTION);
        Set<String> rejected = new HashSet<>();
        for (TransferRejectionRec r : rejections) {
            rejected.add(r.aKey + "|" + r.bKey);
        }
        Map<String, String> keys = new HashMap<>();
        Map<String, String> accountNames = new HashMap<>();

        List<Candidate> candidates = new ArrayList<>();
        for (TransactionRec out : outs) {
            for (TransactionRec into : ins) {
                if (out.accountId.equals(into.accountId) || out.amount != into.amount) {
                    continue;
                }
                int days = (int) Math.abs(Dates.daysBetween(out.dueDate, into.dueDate));
                if (days > MAX_DAYS_APART || rejected.contains(pairKey(keyFor(keys, out), keyFor(keys, into)))) {
                    continue;
                }
                int score = 100 - days * 15
                    + hint(out, accountName(accountNames, into.accountId), name)
                    + hint(into, accountName(accountNames, out.accountId), name)
                    + (out.paid == into.paid ? 5 : 0);
                candidates.add(new Candidate(out, into, days, score));
            }
        }
        candidates.sort((a, b) -> {
            if (a.score != b.score) {
                return Integer.compare(b.score, a.score);
            }
            int c = cmp(a.out.dueDate, b.out.dueDate);
            return c != 0 ? c : byCreation(a.out, b.out);
        });

        Set<String> used = new HashSet<>();
        List<TransferSuggestion> items = new ArrayList<>();
        for (Candidate c : candidates) {
            if (used.contains(c.out.id) || used.contains(c.into.id)) {
                continue;
            }
            used.add(c.out.id);
            used.add(c.into.id);
            TransferSuggestion sug = new TransferSuggestion();
            sug.from = Mappers.transactionDto(c.out);
            sug.to = Mappers.transactionDto(c.into);
            sug.daysApart = c.days;
            sug.score = c.score;
            items.add(sug);
        }
        items.sort((a, b) -> cmp(b.from.dueDate, a.from.dueDate));
        TransferSuggestionList list = new TransferSuggestionList();
        list.items = items;
        list.rejected = rejections.size();
        return list;
    }

    private static String keyFor(Map<String, String> cache, TransactionRec t) {
        String k = cache.get(t.id);
        if (k == null) {
            k = keyOf(t);
            cache.put(t.id, k);
        }
        return k;
    }

    private static String accountName(Map<String, String> cache, String id) {
        String n = cache.get(id);
        if (n == null) {
            AccountRec a = Ctx.db().get(Entity.ACCOUNT, id);
            n = a == null || a.name == null ? "" : a.name;
            cache.put(id, n);
        }
        return n;
    }

    /** Pontos extras quando a descrição parece uma transferência ou cita a outra conta / o próprio usuário. */
    private static int hint(TransactionRec t, String otherAccountName, String userName) {
        String d = Text.normalize(t.description);
        int score = 0;
        for (String w : TRANSFER_WORDS) {
            if (d.contains(w)) {
                score += 20;
                break;
            }
        }
        String otherName = Text.normalize(otherAccountName);
        if (otherName.length() >= 3 && d.contains(otherName)) {
            score += 15;
        }
        if (userName.length() >= 3 && d.contains(userName)) {
            score += 10;
        }
        return score;
    }
}
