package br.com.contaslt.notify;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import br.com.contaslt.data.schema.AccountRec;
import br.com.contaslt.data.schema.CategoryRec;
import br.com.contaslt.data.schema.Entity;
import br.com.contaslt.data.schema.TransactionRec;
import br.com.contaslt.domain.CategorySuggester;
import br.com.contaslt.domain.Dates;
import br.com.contaslt.domain.Matcher;
import br.com.contaslt.dto.Transaction;
import br.com.contaslt.dto.TransactionInput;
import br.com.contaslt.services.Ctx;
import br.com.contaslt.services.Mappers;
import br.com.contaslt.services.RecurringService;
import br.com.contaslt.services.TransactionsService;

/**
 * Transforma uma notificação lida em sugestão de lançamento, usando as mesmas regras do app: conta pelo final do
 * cartão (ou a conta padrão do app do banco), categoria pelas regras de palavra-chave, vencimento recorrente pendente
 * que o valor parece pagar e lançamento igual já existente. Roda sobre os dados locais (fora da thread principal).
 */
public final class InboxService {

    /** Sugestão pronta para confirmar ou abrir no formulário. */
    public static final class Suggestion {
        public TransactionInput input;
        public String accountName;
        public String categoryName;
        public String categoryIcon;
        public String categoryColor;
        /** Vencimento recorrente pendente que este movimento parece pagar. */
        public String occurrenceId;
        public String occurrenceDescription;
        public String occurrenceDate;
        public Long occurrenceAmount;
        /** Lançamento que já parece ser este mesmo movimento. */
        public String duplicateOfId;
        public String duplicateOfDescription;
        public String duplicateOfDate;
    }

    private InboxService() {
    }

    public static Suggestion suggest(InboxItem item, String defaultAccountId, String defaultCardId) {
        Suggestion s = new Suggestion();
        TransactionInput in = new TransactionInput();
        in.description = item.description;
        in.amount = item.amount;
        in.type = item.type;
        in.dueDate = item.date;
        in.paid = true;
        in.merchant = item.counterpart;
        in.notes = "Via notificação" + (item.appLabel != null ? " · " + item.appLabel : "");

        AccountRec account = account(item, defaultAccountId, defaultCardId);
        in.accountId = account == null ? null : account.id;
        s.accountName = account == null ? null : account.name;

        List<CategoryRec> categories = Ctx.db().list(Entity.CATEGORY);
        CategoryRec category = new CategorySuggester(categories, Ctx.db().list(Entity.CATEGORY_RULE))
            .suggest(item.counterpart != null ? item.counterpart : item.description, item.type, null);
        in.categoryId = category == null ? null : category.id;
        if (category != null) {
            s.categoryName = category.name;
            s.categoryIcon = category.icon;
            s.categoryColor = category.color;
        }

        Matcher.Candidate cand = new Matcher.Candidate(
            (item.counterpart == null ? "" : item.counterpart) + " " + (item.title == null ? "" : item.title) + " " + item.description,
            item.amount, item.type, item.date);
        Map<Matcher.Candidate, RecurringService.ScoredOccurrence> found =
            RecurringService.matchPending(Collections.singletonList(cand), c -> c, item.date, item.date);
        RecurringService.ScoredOccurrence occ = found.get(cand);
        if (occ != null) {
            s.occurrenceId = occ.occurrence.id;
            s.occurrenceDescription = occ.occurrence.description;
            s.occurrenceDate = occ.occurrence.dueDate;
            s.occurrenceAmount = occ.occurrence.amount;
            if (in.categoryId == null) {
                in.categoryId = occ.occurrence.categoryId;
            }
        }

        TransactionRec dup = duplicate(item, occ == null ? null : occ.occurrence.id);
        if (dup != null) {
            s.duplicateOfId = dup.id;
            s.duplicateOfDescription = dup.description;
            s.duplicateOfDate = dup.dueDate;
        }
        s.input = in;
        return s;
    }

    /** Confirma: paga o vencimento recorrente casado, ou cria o lançamento. Devolve o lançamento. */
    public static Transaction confirm(Suggestion s) {
        if (s.occurrenceId != null) {
            TransactionRec occ = TransactionsService.requireTransaction(s.occurrenceId).copy();
            occ = RecurringService.settle(occ, s.input.amount, s.input.dueDate);
            if (occ.merchant == null) {
                occ.merchant = s.input.merchant;
            }
            if (occ.categoryId == null) {
                occ.categoryId = s.input.categoryId;
            }
            if (s.input.accountId != null) {
                occ.accountId = s.input.accountId;
            }
            Ctx.db().put(Entity.TRANSACTION, occ);
            return Mappers.transactionDto(occ, true, null);
        }
        if (s.input.accountId == null) {
            throw new Ctx.BusinessException("Escolha a conta: toque em Editar para completar o lançamento.");
        }
        return TransactionsService.createTransaction(s.input).transaction;
    }

    /**
     * Conta do movimento. Compra no crédito: o cartão cujo nome tem o final lido, o cartão padrão do app ou o único
     * cartão cadastrado. O resto (Pix, débito, boleto, transferência): a conta padrão do app ou a primeira conta ativa.
     */
    static AccountRec account(InboxItem item, String defaultAccountId, String defaultCardId) {
        List<AccountRec> accounts = Ctx.db().list(Entity.ACCOUNT);
        List<AccountRec> cards = new ArrayList<>();
        for (AccountRec a : accounts) {
            if (!a.archived && a.isCreditCard()) {
                cards.add(a);
            }
        }
        String text = BankParser.fold((item.title == null ? "" : item.title) + " " + (item.text == null ? "" : item.text));
        boolean credit = "CARD".equals(item.kind) && !text.contains("debito");
        if (credit) {
            if (item.cardLast4 != null) {
                for (AccountRec c : cards) {
                    if (c.name != null && c.name.contains(item.cardLast4)) {
                        return c;
                    }
                }
            }
            AccountRec card = Ctx.db().get(Entity.ACCOUNT, defaultCardId);
            if (card != null && !card.archived) {
                return card;
            }
            if (cards.size() == 1) {
                return cards.get(0);
            }
        }
        AccountRec def = Ctx.db().get(Entity.ACCOUNT, defaultAccountId);
        if (def != null && !def.archived) {
            return def;
        }
        for (AccountRec a : accounts) {
            if (!a.archived && !a.isCreditCard()) {
                return a;
            }
        }
        return null;
    }

    /** Mesmo tipo e valor, até um dia de distância (ex.: já lançado à mão ou por outra notificação). */
    static TransactionRec duplicate(InboxItem item, String ignoreId) {
        for (TransactionRec t : Ctx.db().list(Entity.TRANSACTION)) {
            if (t.id.equals(ignoreId) || !item.type.equals(t.type) || t.amount != item.amount || t.transferId != null) {
                continue;
            }
            if (Math.abs(Dates.daysBetween(t.dueDate, item.date)) <= 1 && t.paid) {
                return t;
            }
        }
        return null;
    }
}
