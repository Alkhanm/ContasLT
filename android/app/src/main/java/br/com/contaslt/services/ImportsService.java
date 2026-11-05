package br.com.contaslt.services;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import br.com.contaslt.data.LocalStore;
import br.com.contaslt.data.schema.AccountRec;
import br.com.contaslt.data.schema.CategoryRec;
import br.com.contaslt.data.schema.Entity;
import br.com.contaslt.data.schema.ImportBatchRec;
import br.com.contaslt.data.schema.TransactionRec;
import br.com.contaslt.domain.CategorySuggester;
import br.com.contaslt.domain.Dates;
import br.com.contaslt.domain.Excel;
import br.com.contaslt.domain.Ids;
import br.com.contaslt.domain.Invoice;
import br.com.contaslt.domain.Matcher;
import br.com.contaslt.domain.Ofx;
import br.com.contaslt.domain.ParseException;
import br.com.contaslt.domain.ParsedRow;
import br.com.contaslt.domain.Text;
import br.com.contaslt.dto.CardInvoice;
import br.com.contaslt.dto.ConfirmRow;
import br.com.contaslt.dto.ImportBatch;
import br.com.contaslt.dto.ImportConfirmRequest;
import br.com.contaslt.dto.ImportPreview;
import br.com.contaslt.dto.ImportResult;
import br.com.contaslt.dto.PreviewRow;

/**
 * Importação de extratos OFX e planilhas (porte de frontend/src/services/imports.ts): prévia com duplicados, sugestão
 * de categoria, casamento com lançamentos manuais e vencimentos de recorrentes, e pagamento de fatura de cartão;
 * confirmação cria (ou vincula) os lançamentos num lote, que pode ser desfeito.
 */
public final class ImportsService {

    /** Janela de dias entre a data do extrato e a do lançamento manual para considerá-los a mesma compra. */
    public static final int MATCH_DAYS = 3;

    private static final List<String> INVOICE_WORDS = Arrays.asList("fatura", "pgto cartao", "pagto cartao",
        "pagamento cartao", "pag cartao", "pagamento de cartao", "cartao de credito", "cartao credito");

    private static final List<String> PAYMENT_RECEIVED_WORDS = Arrays.asList(
        "pagamento recebido", "pagamento efetuado", "pagto", "pgto", "fatura", "pagamento");

    private ImportsService() {
    }

    private static AccountRec requireAccount(String id) {
        return Ctx.require(Ctx.db().get(Entity.ACCOUNT, id), "Conta não encontrada");
    }

    /** Erro de leitura do arquivo vira mensagem para o usuário (422). */
    private static List<ParsedRow> parse(String source, byte[] bytes) {
        try {
            return "OFX".equals(source) ? Ofx.parseOfx(bytes) : Excel.parseExcel(bytes);
        } catch (ParseException e) {
            throw new Ctx.BusinessException(e.getMessage());
        }
    }

    private static int cmp(String a, String b) {
        return Integer.signum(a.compareTo(b));
    }

    public static ImportPreview previewImport(String accountId, String filename, byte[] bytes) {
        AccountRec account = requireAccount(accountId);
        String name = filename != null ? filename : "arquivo";
        String source = detectSource(name, bytes);
        List<ParsedRow> parsed = new ArrayList<>(parse(source, bytes));
        parsed.sort((a, b) -> cmp(b.date, a.date));

        LocalStore s = Ctx.db();
        List<CategoryRec> categories = new ArrayList<>(s.list(Entity.CATEGORY));
        categories.sort((a, b) -> {
            int c = cmp(a.type, b.type);
            return c != 0 ? c : CatalogService.COLLATOR.compare(a.name, b.name);
        });
        CategorySuggester suggester = new CategorySuggester(categories, s.list(Entity.CATEGORY_RULE));

        Set<String> externalIds = new HashSet<>();
        for (ParsedRow p : parsed) {
            if (p.externalId != null) {
                externalIds.add(p.externalId);
            }
        }
        List<TransactionRec> txs = s.list(Entity.TRANSACTION);
        Set<String> existingExternal = new HashSet<>();
        if (!externalIds.isEmpty()) {
            for (TransactionRec t : txs) {
                if (t.accountId.equals(account.id) && t.externalId != null && externalIds.contains(t.externalId)) {
                    existingExternal.add(t.externalId);
                }
            }
        }
        String min = parsed.isEmpty() ? Dates.today() : parsed.get(0).date;
        String max = min;
        for (ParsedRow p : parsed) {
            min = Dates.minDate(min, p.date);
            max = Dates.maxDate(max, p.date);
        }
        Set<String> existingSignatures = new HashSet<>();
        for (TransactionRec t : txs) {
            if (t.accountId.equals(account.id) && t.dueDate.compareTo(min) >= 0 && t.dueDate.compareTo(max) <= 0) {
                existingSignatures.add(signature(t.dueDate, t.amount, t.description));
            }
        }

        Map<ParsedRow, TransactionRec> matches = matchExisting(account, parsed, min, max);
        List<AccountRec> cards = new ArrayList<>();
        if (!CardsService.isCreditCard(account)) {
            for (AccountRec a : s.list(Entity.ACCOUNT)) {
                if (CardsService.isCreditCard(a) && !a.archived) {
                    cards.add(a);
                }
            }
            cards.sort((a, b) -> CatalogService.COLLATOR.compare(a.name, b.name));
        }
        List<TransactionRec> current = s.list(Entity.TRANSACTION);

        List<PreviewRow> rows = new ArrayList<>();
        int duplicates = 0;
        int index = 0;
        for (ParsedRow p : parsed) {
            boolean duplicate = (p.externalId != null && existingExternal.contains(p.externalId))
                || existingSignatures.contains(signature(p.date, p.amount, p.description));
            if (duplicate) {
                duplicates++;
            }
            boolean paymentReceived = !duplicate && CardsService.isCreditCard(account) && isInvoicePaymentReceived(p);
            CategoryRec suggestion = suggester.suggest(p.description, p.type, p.categoryHint);
            TransactionRec m = duplicate ? null : matches.get(p);
            CategoryRec mc = m != null ? s.get(Entity.CATEGORY, m.categoryId) : null;
            AccountRec card = duplicate || m != null ? null : invoiceCardFor(p, cards);
            CardInvoice inv = card != null
                ? CardsService.summarizeInvoice(card, Invoice.inferInvoiceMonth(card, p.date), current) : null;
            PreviewRow r = new PreviewRow();
            r.index = index++;
            r.date = p.date;
            r.description = p.description;
            r.amount = p.amount;
            r.type = p.type;
            r.externalId = p.externalId;
            r.duplicate = duplicate;
            r.suggestedCategoryId = suggestion != null ? suggestion.id : null;
            r.suggestedCategoryName = suggestion != null ? suggestion.name : null;
            r.suggestedCategoryColor = suggestion != null ? suggestion.color : null;
            r.suggestedCategoryIcon = suggestion != null ? suggestion.icon : null;
            r.matchTransactionId = m != null ? m.id : null;
            r.matchDescription = m != null ? m.description : null;
            r.matchDate = m != null ? m.dueDate : null;
            AccountRec ma = m != null ? s.get(Entity.ACCOUNT, m.accountId) : null;
            r.matchAccountName = ma != null ? ma.name : null;
            r.matchItemCount = m != null && m.items != null ? m.items.size() : 0;
            r.matchHasReceipt = m != null && m.receiptFile != null;
            r.matchCategoryId = mc != null ? mc.id : null;
            r.matchCategoryName = mc != null ? mc.name : null;
            r.matchRecurring = m != null && m.recurringBillId != null;
            r.matchAmount = m != null ? m.amount : null;
            r.invoiceCardId = inv != null ? card.id : null;
            r.invoiceCardName = inv != null ? card.name : null;
            r.invoiceMonth = inv != null ? inv.month : null;
            r.invoiceDueDate = inv != null ? inv.dueDate : null;
            r.invoiceTotal = inv != null ? inv.purchases : null;
            r.invoicePaid = inv != null ? inv.paid : null;
            r.invoicePaymentReceived = paymentReceived;
            rows.add(r);
        }
        ImportPreview out = new ImportPreview();
        out.source = source;
        out.filename = name;
        out.accountId = account.id;
        out.total = rows.size();
        out.duplicates = duplicates;
        out.rows = rows;
        return out;
    }

    /**
     * No extrato do próprio cartão, a entrada "pagamento recebido" é o outro lado da fatura paga pela conta; não deve
     * virar receita nem estorno de compra.
     */
    private static boolean isInvoicePaymentReceived(ParsedRow row) {
        if (!"INCOME".equals(row.type)) {
            return false;
        }
        String d = Text.normalize(row.description);
        for (String w : PAYMENT_RECEIVED_WORDS) {
            if (d.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Linha do extrato da conta que parece ser o pagamento da fatura de um cartão: despesa cuja descrição fala em
     * fatura/pagamento de cartão ou cita o nome de um cartão cadastrado. O cartão citado pelo nome tem preferência;
     * sem citação, vale o único cartão, ou o primeiro.
     */
    private static AccountRec invoiceCardFor(ParsedRow row, List<AccountRec> cards) {
        if (cards.isEmpty() || !"EXPENSE".equals(row.type)) {
            return null;
        }
        String d = " " + Text.normalize(row.description) + " ";
        AccountRec named = null;
        for (AccountRec c : cards) {
            for (String token : Text.normalize(c.name).split(" ", -1)) {
                if (token.length() >= 4 && !token.equals("cartao") && !token.equals("credito") && d.contains(token)) {
                    named = c;
                }
            }
        }
        boolean invoiceWord = false;
        for (String w : INVOICE_WORDS) {
            if (d.contains(w)) {
                invoiceWord = true;
                break;
            }
        }
        if (named != null && (invoiceWord || d.contains("pagamento") || d.contains("pgto") || d.contains("pagto"))) {
            return named;
        }
        return invoiceWord ? (named != null ? named : cards.get(0)) : null;
    }

    private static final class Pair {
        final ParsedRow row;
        final TransactionRec tx;
        final int score;

        Pair(ParsedRow row, TransactionRec tx, int score) {
            this.row = row;
            this.tx = tx;
            this.score = score;
        }
    }

    /**
     * Procura, para cada linha do extrato, um lançamento criado à mão (comprovante, lançamento manual) com o mesmo
     * valor e tipo, até {@link #MATCH_DAYS} dias de distância. Cada lançamento casa com no máximo uma linha; em caso
     * de empate vence a mesma conta, a data mais próxima e a descrição mais parecida.
     */
    private static Map<ParsedRow, TransactionRec> matchExisting(AccountRec account, List<ParsedRow> parsed, String min,
                                                                String max) {
        LocalStore s = Ctx.db();
        String from = Dates.plusDays(min, -MATCH_DAYS);
        String to = Dates.plusDays(max, MATCH_DAYS);
        boolean creditCard = CardsService.isCreditCard(account);
        // Compras de cartão só casam com extrato de cartão, e lançamentos de conta só com extrato de conta.
        List<TransactionRec> candidates = new ArrayList<>();
        for (TransactionRec t : s.list(Entity.TRANSACTION)) {
            if (Text.present(t.importBatchId) || t.externalId != null || Text.present(t.transferId)
                || Text.present(t.recurringBillId)) {
                continue;
            }
            if (t.dueDate.compareTo(from) < 0 || t.dueDate.compareTo(to) > 0) {
                continue;
            }
            AccountRec a = s.get(Entity.ACCOUNT, t.accountId);
            if (a != null && CardsService.isCreditCard(a) == creditCard) {
                candidates.add(t);
            }
        }
        List<Pair> pairs = new ArrayList<>();
        for (ParsedRow row : parsed) {
            Set<String> rowTokens = tokens(row.description);
            for (TransactionRec tx : candidates) {
                if (!tx.type.equals(row.type) || tx.amount != row.amount) {
                    continue;
                }
                long days = Math.abs(Dates.daysBetween(tx.dueDate, row.date));
                if (days > MATCH_DAYS) {
                    continue;
                }
                int score = (int) (100 - days * 15)
                    + (tx.accountId.equals(account.id) ? 30 : 0)
                    + (tx.receiptFile != null || (tx.items != null && !tx.items.isEmpty()) ? 10 : 0);
                Set<String> txTokens = tokens(tx.description + " " + (tx.merchant != null ? tx.merchant : ""));
                for (String t : rowTokens) {
                    if (txTokens.contains(t)) {
                        score += 25;
                        break;
                    }
                }
                pairs.add(new Pair(row, tx, score));
            }
        }
        // Vencimentos pendentes de contas recorrentes: nome como chave, valor e data dentro da precisão da regra.
        Map<ParsedRow, RecurringService.ScoredOccurrence> occurrences = RecurringService.matchPending(parsed,
            p -> new Matcher.Candidate(p.description, p.amount, p.type, p.date), min, max);
        for (Map.Entry<ParsedRow, RecurringService.ScoredOccurrence> e : occurrences.entrySet()) {
            RecurringService.ScoredOccurrence so = e.getValue();
            pairs.add(new Pair(e.getKey(), so.occurrence,
                so.score + (so.occurrence.accountId.equals(account.id) ? 30 : 0)));
        }
        pairs.sort((a, b) -> Integer.compare(b.score, a.score));
        Map<ParsedRow, TransactionRec> result = new IdentityHashMap<>();
        Set<String> usedTx = new HashSet<>();
        for (Pair pair : pairs) {
            if (result.containsKey(pair.row) || usedTx.contains(pair.tx.id)) {
                continue;
            }
            result.put(pair.row, pair.tx);
            usedTx.add(pair.tx.id);
        }
        return result;
    }

    private static Set<String> tokens(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String t : Text.normalize(text).split(" ", -1)) {
            if (t.length() >= 4) {
                out.add(t);
            }
        }
        return out;
    }

    // ---------- Confirmação ----------

    private static int len(String s) {
        return s == null ? 0 : s.length();
    }

    /** Mesmas regras do Bean Validation do ImportConfirmRequest/ConfirmRow (mensagens padrão em pt-BR). */
    private static void checkRequest(ImportConfirmRequest req) {
        Ctx.Checks checks = Ctx.validate()
            .check("accountId", req.accountId != null, "Escolha a conta")
            .check("source", req.source != null, "não deve ser nulo")
            .check("filename", req.filename == null || req.filename.length() <= 255, "tamanho deve ser entre 0 e 255")
            .check("rows", req.rows != null && !req.rows.isEmpty(), "Selecione ao menos um lançamento");
        List<ConfirmRow> rows = req.rows != null ? req.rows : new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            ConfirmRow r = rows.get(i);
            String f = "rows[" + i + "].";
            checks.check(f + "date", r.date != null, "não deve ser nulo")
                .check(f + "description", r.description != null && !Text.trim(r.description).isEmpty(),
                    "não deve estar em branco")
                .check(f + "description", r.description == null || r.description.length() <= 140,
                    "tamanho deve ser entre 0 e 140")
                .check(f + "amount", r.amount != null, "não deve ser nulo")
                .check(f + "amount", r.amount == null || r.amount > 0, "deve ser maior que 0")
                .check(f + "type", r.type != null, "não deve ser nulo")
                .check(f + "externalId", r.externalId == null || r.externalId.length() <= 120,
                    "tamanho deve ser entre 0 e 120");
        }
        checks.throwIfAny();
    }

    private static final class Learned {
        final String keyword;
        final String categoryId;

        Learned(String keyword, String categoryId) {
            this.keyword = keyword;
            this.categoryId = categoryId;
        }
    }

    /**
     * Cria os lançamentos do lote (ou vincula linhas a lançamentos existentes). Tudo ou nada, como a transação do
     * servidor: as gravações só acontecem depois que todas as linhas passaram nas regras.
     */
    public static ImportResult confirmImport(ImportConfirmRequest req) {
        checkRequest(req);
        LocalStore s = Ctx.db();
        AccountRec account = requireAccount(req.accountId);
        ImportBatchRec batch = new ImportBatchRec();
        batch.id = Ids.newId();
        batch.accountId = account.id;
        batch.source = req.source;
        batch.filename = Text.truncate(req.filename, 255);
        batch.importedCount = 0;
        batch.createdAt = Dates.nowIso();

        List<TransactionRec> writes = new ArrayList<>();
        List<Learned> learned = new ArrayList<>();
        Set<String> seenExternal = new HashSet<>();
        Set<String> linkedIds = new HashSet<>();
        int imported = 0;
        int skipped = 0;
        int linked = 0;
        int invoicePayments = 0;
        for (ConfirmRow row : req.rows) {
            String externalId = row.externalId == null || Text.trim(row.externalId).isEmpty()
                ? null : Text.trim(row.externalId);
            if (externalId != null && (seenExternal.contains(externalId) || existsExternal(account.id, externalId))) {
                skipped++;
                continue;
            }
            if (externalId != null) {
                seenExternal.add(externalId);
            }
            long amount = row.amount;
            if (row.linkTransactionId != null) {
                TransactionRec found = s.get(Entity.TRANSACTION, row.linkTransactionId);
                if (found == null) {
                    throw new Ctx.NotFoundException("Lançamento a vincular não encontrado");
                }
                TransactionRec existing = found.copy();
                if (existing.importBatchId != null || linkedIds.contains(existing.id)) {
                    throw new Ctx.BusinessException(
                        "O lançamento \"" + existing.description + "\" já está vinculado a uma importação");
                }
                linkedIds.add(existing.id);
                if (!existing.type.equals(row.type)) {
                    throw new Ctx.BusinessException(
                        "O lançamento \"" + existing.description + "\" não é do mesmo tipo da linha do extrato");
                }
                if (existing.recurringBillId != null) {
                    // Vencimento previsto vira o pagamento real: valor e data são os do extrato.
                    existing = RecurringService.settle(existing, amount, row.date);
                    if (existing.categoryId == null && row.categoryId != null) {
                        existing.categoryId = CatalogService.requireCategoryOrNull(row.categoryId).id;
                    }
                } else if (existing.amount != amount) {
                    throw new Ctx.BusinessException(
                        "O lançamento \"" + existing.description + "\" não tem o mesmo valor da linha do extrato");
                }
                // O lançamento manual (com itens, comprovante, categoria) passa a representar a linha do extrato.
                existing.accountId = account.id;
                existing.importBatchId = batch.id;
                existing.externalId = externalId;
                existing.linkedImport = true;
                existing.paid = true;
                writes.add(existing);
                linked++;
                continue;
            }
            CategoryRec category = CatalogService.requireCategoryOrNull(row.categoryId);
            if (category != null && !category.type.equals(row.type)) {
                throw new Ctx.BusinessException("Categoria \"" + category.name + "\" não é do tipo do lançamento \""
                    + row.description + "\"");
            }
            TransactionRec t = TransactionsService.emptyTransaction();
            t.accountId = account.id;
            t.categoryId = category != null ? category.id : null;
            t.importBatchId = batch.id;
            t.description = Text.trim(row.description);
            t.amount = amount;
            t.type = row.type;
            t.dueDate = row.date;
            t.paid = row.paid;
            t.externalId = externalId;
            if (row.invoiceCardId != null) {
                if (!"EXPENSE".equals(row.type) || CardsService.isCreditCard(account)) {
                    throw new Ctx.BusinessException("Pagamento de fatura é uma saída da conta: \"" + row.description + "\"");
                }
                AccountRec card = CardsService.requireCard(row.invoiceCardId);
                t.cardAccountId = card.id;
                t.cardInvoiceMonth = Dates.firstDay(Text.present(row.invoiceMonth)
                    ? row.invoiceMonth.substring(0, Math.min(7, row.invoiceMonth.length()))
                    : Invoice.inferInvoiceMonth(card, row.date));
                invoicePayments++;
            }
            writes.add(t);
            imported++;

            if (category != null && row.learnRule) {
                learned.add(new Learned(row.description, category.id));
            }
        }

        batch.importedCount = imported + linked;
        s.put(Entity.IMPORT_BATCH, batch);
        for (TransactionRec t : writes) {
            s.put(Entity.TRANSACTION, t);
        }
        for (Learned l : learned) {
            learn(l.categoryId, l.keyword);
        }
        ImportResult r = new ImportResult();
        r.batchId = batch.id;
        r.imported = imported;
        r.skipped = skipped;
        r.linked = linked;
        r.invoicePayments = invoicePayments;
        return r;
    }

    private static boolean existsExternal(String accountId, String externalId) {
        for (TransactionRec t : Ctx.db().list(Entity.TRANSACTION)) {
            if (t.accountId.equals(accountId) && externalId.equals(t.externalId)) {
                return true;
            }
        }
        return false;
    }

    private static void learn(String categoryId, String description) {
        String keyword = Text.truncate(Text.normalize(description), 120);
        if (keyword.length() < 3) {
            return;
        }
        CatalogService.saveRule(keyword, categoryId);
    }

    // ---------- Histórico ----------

    public static List<ImportBatch> importHistory() {
        LocalStore s = Ctx.db();
        List<ImportBatchRec> batches = new ArrayList<>(s.list(Entity.IMPORT_BATCH));
        batches.sort((a, b) -> {
            int c = cmp(b.createdAt, a.createdAt);
            return c != 0 ? c : cmp(b.id, a.id);
        });
        List<ImportBatch> out = new ArrayList<>();
        for (int i = 0; i < Math.min(50, batches.size()); i++) {
            ImportBatchRec b = batches.get(i);
            ImportBatch d = new ImportBatch();
            d.id = b.id;
            d.source = b.source;
            d.filename = b.filename;
            d.accountId = b.accountId;
            AccountRec a = s.get(Entity.ACCOUNT, b.accountId);
            d.accountName = a != null ? a.name : "";
            d.importedCount = b.importedCount;
            d.createdAt = b.createdAt;
            out.add(d);
        }
        return out;
    }

    /** Exclui o lote e todos os lançamentos criados por ele. Retorna quantos lançamentos foram removidos. */
    public static int deleteImport(String id) {
        LocalStore s = Ctx.db();
        ImportBatchRec batch = Ctx.require(s.get(Entity.IMPORT_BATCH, id), "Importação não encontrada");
        int removed = 0;
        for (TransactionRec t : new ArrayList<>(s.list(Entity.TRANSACTION))) {
            if (!batch.id.equals(t.importBatchId)) {
                continue;
            }
            if (t.linkedImport) {
                // Lançamentos que já existiam e só foram vinculados ao extrato continuam; apenas perdem o vínculo.
                TransactionRec next = t.copy();
                next.importBatchId = null;
                next.externalId = null;
                next.linkedImport = false;
                s.put(Entity.TRANSACTION, next);
            } else {
                // Como o ON DELETE do banco: a transferência some e a outra perna fica solta.
                TransactionsService.deleteTransaction(t.id);
                removed++;
            }
        }
        s.remove(Entity.IMPORT_BATCH, batch.id);
        return removed;
    }

    // ---------- Auxiliares ----------

    public static String detectSource(String filename, byte[] bytes) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".ofx") || lower.endsWith(".qfx")) {
            return "OFX";
        }
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls") || lower.endsWith(".xlsm")) {
            return "EXCEL";
        }
        String head = Ofx.latin1(bytes, 400).toUpperCase(Locale.ROOT);
        if (head.contains("OFXHEADER") || head.contains("<OFX")) {
            return "OFX";
        }
        if (bytes.length > 4 && bytes[0] == 0x50 && bytes[1] == 0x4b) {
            return "EXCEL";
        }
        throw new Ctx.BusinessException("Formato não suportado. Envie um arquivo .ofx, .xlsx ou .xls");
    }

    private static String signature(String date, long amount, String description) {
        return date + "|" + amount + "|" + Text.normalize(description);
    }
}
