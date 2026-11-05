package br.com.contaslt.services;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import br.com.contaslt.data.Api;
import br.com.contaslt.data.ApiException;
import br.com.contaslt.data.LocalStore;
import br.com.contaslt.data.schema.CategoryRec;
import br.com.contaslt.data.schema.Entity;
import br.com.contaslt.data.schema.ItemRec;
import br.com.contaslt.data.schema.TransactionRec;
import br.com.contaslt.domain.CategorySuggester;
import br.com.contaslt.domain.Dates;
import br.com.contaslt.domain.Matcher;
import br.com.contaslt.domain.Money;
import br.com.contaslt.domain.Text;
import br.com.contaslt.dto.ReceiptScan;
import br.com.contaslt.dto.ScanItem;
import br.com.contaslt.dto.Transaction;

/**
 * Comprovantes (porte de frontend/src/services/receipts.ts). A foto vai para o servidor, que guarda, lê o QR code e
 * roda o modelo de visão (ou põe na fila do worker); de volta vem só o que foi lido. Tudo o que depende dos dados do
 * usuário é calculado aqui, como antes era no servidor: data e total do lançamento, categoria pelas regras,
 * duplicata e vencimento recorrente que o cupom parece pagar. A leitura adiada (worker) é aplicada no lançamento
 * salvo por {@link #processPendingReceipts()}.
 */
public final class ReceiptsService {

    private static volatile Api api;

    private ReceiptsService() {
    }

    /** O que o modelo leu (ReceiptScan do servidor; valores em reais). */
    public static class RawScan {
        public String merchant;
        public String issueDate;
        public String dueDate;
        public boolean paid;
        public Double total;
        public String paymentMethod;
        public String accessKey;
        public List<RawItem> items = new ArrayList<>();
    }

    /** Item lido (valores em reais). */
    public static class RawItem {
        public String description;
        public Double quantity;
        public Double unitPrice;
        public double total;

        public RawItem() {
        }

        public RawItem(String description, Double quantity, Double unitPrice, double total) {
            this.description = description;
            this.quantity = quantity;
            this.unitPrice = unitPrice;
            this.total = total;
        }
    }

    /** Resposta de /api/receipts/scan e /api/receipts/jobs/{id}. */
    static class ReceiptRead {
        String receiptFile;
        Long jobId;
        /** DONE, PENDING ou FAILED. */
        String status;
        boolean workerOnline;
        String error;
        String qrAccessKey;
        RawScan scan;
    }

    /** Resposta de /api/receipts/attach e /api/receipts/retry. */
    static class JobResult {
        long jobId;
        String transactionId;
        String receiptFile;
        String status;
        String error;
        String qrAccessKey;
        RawScan scan;
    }

    static class UploadResult {
        String receiptFile;
    }

    /** Chamado uma vez na abertura do app. */
    public static void setApi(Api a) {
        api = a;
    }

    private static Api api() {
        Api a = api;
        if (a == null) {
            throw new IllegalStateException("Cliente do servidor não configurado");
        }
        return a;
    }

    private static List<RawItem> items(RawScan scan) {
        return scan.items != null ? scan.items : Collections.<RawItem>emptyList();
    }

    // ---------- Chave de acesso (porte de AccessKey) ----------

    public static boolean isValidAccessKey(String key) {
        if (key == null || !key.matches("^\\d{44}$")) {
            return false;
        }
        int sum = 0;
        int weight = 2;
        for (int i = 42; i >= 0; i--) {
            sum += (key.charAt(i) - '0') * weight;
            weight = weight == 9 ? 2 : weight + 1;
        }
        int dv = 11 - (sum % 11);
        if (dv >= 10) {
            dv = 0;
        }
        return key.charAt(43) - '0' == dv;
    }

    /** O modelo às vezes erra o ano; quando a chave confirma o mês lido, vale o ano da chave. */
    public static String fixYear(String date, String key) {
        if (date == null || date.isEmpty() || !isValidAccessKey(key)) {
            return date;
        }
        int year = 2000 + Integer.parseInt(key.substring(2, 4));
        int month = Integer.parseInt(key.substring(4, 6));
        if (date.length() >= 7 && month == parseIntOrNaN(date.substring(5, 7))
            && year != parseIntOrNaN(date.substring(0, Math.min(4, date.length())))) {
            return String.format(Locale.ROOT, "%04d", year) + date.substring(4);
        }
        return date;
    }

    /** Number() do JavaScript para os pedaços da data: texto não numérico nunca é igual. */
    private static int parseIntOrNaN(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }

    // ---------- Interpretação da leitura ----------

    private static long itemsTotalCents(RawScan scan) {
        long s = 0;
        for (RawItem i : items(scan)) {
            s += Money.toCents(i.total);
        }
        return s;
    }

    /** Total do cupom; sem ele, a soma dos itens. */
    private static Long totalCents(RawScan scan) {
        if (scan.total != null && scan.total > 0) {
            return Money.toCents(scan.total);
        }
        long items = itemsTotalCents(scan);
        return items > 0 ? items : null;
    }

    /** Data do lançamento: vencimento (fatura); senão emissão/compra, com o ano corrigido pela chave. */
    private static String readDate(RawScan scan, String key) {
        return scan.dueDate != null ? scan.dueDate : fixYear(scan.issueDate, key);
    }

    private static String firstDescriptions(RawScan scan) {
        StringBuilder sb = new StringBuilder();
        List<RawItem> list = items(scan);
        for (int i = 0; i < Math.min(10, list.size()); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(list.get(i).description);
        }
        return sb.toString();
    }

    /** Categoria pelas regras do usuário: primeiro pelo estabelecimento, depois pelos itens. */
    public static CategoryRec suggestCategory(RawScan scan) {
        LocalStore s = Ctx.db();
        CategorySuggester suggester = new CategorySuggester(s.list(Entity.CATEGORY), s.list(Entity.CATEGORY_RULE));
        CategoryRec category = suggester.suggest(scan.merchant, "EXPENSE", null);
        if (category == null && !items(scan).isEmpty()) {
            category = suggester.suggest(firstDescriptions(scan), "EXPENSE", null);
        }
        return category;
    }

    private static boolean sameMerchant(TransactionRec t, String merchant) {
        if (merchant == null || Text.trim(merchant).isEmpty()) {
            return false;
        }
        String a = Text.trim(merchant).toLowerCase(Locale.ROOT);
        return (t.merchant != null && Text.trim(t.merchant).toLowerCase(Locale.ROOT).equals(a))
            || (t.description != null && Text.trim(t.description).toLowerCase(Locale.ROOT).equals(a));
    }

    private static boolean sameItems(TransactionRec t, RawScan scan) {
        List<RawItem> scanItems = items(scan);
        List<ItemRec> txItems = t.items != null ? t.items : Collections.<ItemRec>emptyList();
        if (scanItems.isEmpty() || txItems.size() != scanItems.size()) {
            return false;
        }
        List<Long> a = new ArrayList<>();
        for (ItemRec i : txItems) {
            a.add(i.total);
        }
        List<Long> b = new ArrayList<>();
        for (RawItem i : scanItems) {
            b.add(Money.toCents(i.total));
        }
        Collections.sort(a);
        Collections.sort(b);
        return a.equals(b);
    }

    /** Monta a resposta do formulário (como o {@code complete} do antigo ReceiptService). */
    static ReceiptScan toScanDto(ReceiptRead read, String ignoreTransactionId) {
        ReceiptScan out = new ReceiptScan();
        out.receiptFile = read.receiptFile;
        out.merchant = "";
        out.date = null;
        out.paid = true;
        out.total = null;
        out.itemsTotal = 0;
        out.paymentMethod = "";
        out.accessKey = null;
        out.items = new ArrayList<>();
        out.jobId = read.jobId;
        out.status = read.status;
        out.workerOnline = read.workerOnline;
        out.error = read.error;
        RawScan scan = read.scan;
        if (!"DONE".equals(read.status) || scan == null) {
            return out;
        }

        LocalStore s = Ctx.db();
        String accessKey = read.qrAccessKey != null ? read.qrAccessKey : scan.accessKey;
        String date = readDate(scan, accessKey);
        if (date == null) {
            date = Dates.today();
        }
        Long total = totalCents(scan);
        CategoryRec category = suggestCategory(scan);
        List<TransactionRec> txs = s.list(Entity.TRANSACTION);

        TransactionRec duplicate = null;
        if (accessKey != null) {
            for (TransactionRec t : txs) {
                if (accessKey.equals(t.receiptAccessKey)) {
                    duplicate = t;
                    break;
                }
            }
        }
        if (duplicate == null && total != null) {
            // Sem chave legível, mesma data e mesmo valor já lançados valem como provável duplicata; entre vários,
            // prefere o do mesmo estabelecimento (e os mesmos itens).
            List<TransactionRec> same = new ArrayList<>();
            for (TransactionRec t : txs) {
                if ("EXPENSE".equals(t.type) && date.equals(t.dueDate) && t.amount == total
                    && !t.id.equals(ignoreTransactionId)) {
                    same.add(t);
                }
            }
            Map<String, Integer> similarity = new HashMap<>();
            for (TransactionRec t : same) {
                similarity.put(t.id, (sameMerchant(t, scan.merchant) ? 2 : 0) + (sameItems(t, scan) ? 1 : 0));
            }
            same.sort((a, b) -> {
                int c = Integer.compare(similarity.get(b.id), similarity.get(a.id));
                return c != 0 ? c : (a.createdAt.compareTo(b.createdAt) < 0 ? -1 : 1);
            });
            duplicate = same.isEmpty() ? null : same.get(0);
        }
        if (duplicate != null && duplicate.id.equals(ignoreTransactionId)) {
            duplicate = null;
        }

        // Sem duplicata e lançando algo novo: o comprovante pode pagar um vencimento recorrente pendente.
        TransactionRec occurrence = null;
        if (duplicate == null && ignoreTransactionId == null && total != null) {
            String text = (scan.merchant != null ? scan.merchant : "") + " " + firstDescriptions(scan);
            Matcher.Candidate cand = new Matcher.Candidate(text, total, "EXPENSE", date);
            List<Matcher.Candidate> list = new ArrayList<>();
            list.add(cand);
            RecurringService.ScoredOccurrence so = RecurringService.matchPending(list, c -> c, date, date).get(cand);
            occurrence = so != null ? so.occurrence : null;
        }

        out.merchant = scan.merchant;
        out.date = date;
        out.paid = scan.paid;
        out.total = total;
        out.itemsTotal = itemsTotalCents(scan);
        out.paymentMethod = scan.paymentMethod;
        out.accessKey = accessKey;
        List<ScanItem> items = new ArrayList<>();
        for (RawItem i : items(scan)) {
            ScanItem si = new ScanItem();
            si.description = i.description;
            si.quantity = i.quantity;
            si.unitPrice = Money.toCentsOrNull(i.unitPrice);
            si.total = Money.toCents(i.total);
            items.add(si);
        }
        out.items = items;
        out.suggestedCategoryId = category != null ? category.id : null;
        out.suggestedCategoryName = category != null ? category.name : null;
        out.duplicateOfTransactionId = duplicate != null ? duplicate.id : null;
        out.duplicateOfDescription = duplicate != null ? duplicate.description : null;
        out.duplicateOfDate = duplicate != null ? duplicate.dueDate : null;
        out.recurringMatchTransactionId = occurrence != null ? occurrence.id : null;
        out.recurringMatchDescription = occurrence != null ? occurrence.description : null;
        out.recurringMatchDate = occurrence != null ? occurrence.dueDate : null;
        out.recurringMatchAmount = occurrence != null ? occurrence.amount : null;
        out.recurringMatchCategoryId = occurrence != null ? occurrence.categoryId : null;
        out.recurringMatchAccountId = occurrence != null ? occurrence.accountId : null;
        return out;
    }

    /**
     * Leva o que o modelo leu para um lançamento já salvo, sem sobrescrever o que o usuário preencheu. Total ou data
     * diferentes do lançado viram aviso (DIVERGENT) para o usuário decidir. Porte de ReceiptJobService.applyTo.
     */
    public static TransactionRec applyScan(TransactionRec t, RawScan scan, String qrAccessKey) {
        TransactionRec out = t.copy();
        if (out.items == null) {
            out.items = new ArrayList<>();
        }
        String key = qrAccessKey != null ? qrAccessKey : scan.accessKey;
        if (out.items.isEmpty() && !items(scan).isEmpty()) {
            List<ItemRec> list = new ArrayList<>();
            for (RawItem i : items(scan)) {
                list.add(new ItemRec(Text.truncate(i.description, 140), i.quantity, Money.toCentsOrNull(i.unitPrice),
                    Money.toCents(i.total)));
            }
            out.items = list;
        }
        if ((out.merchant == null || Text.trim(out.merchant).isEmpty())
            && scan.merchant != null && !Text.trim(scan.merchant).isEmpty()) {
            out.merchant = Text.truncate(scan.merchant, 120);
        }
        if (!Text.present(out.receiptAccessKey) && Text.present(key)) {
            out.receiptAccessKey = key;
        }
        if (!Text.present(out.categoryId) && "EXPENSE".equals(out.type)) {
            CategoryRec c = suggestCategory(scan);
            out.categoryId = c != null ? c.id : null;
        }

        Long total = totalCents(scan);
        String date = readDate(scan, key);
        boolean totalDiffers = total != null && total != out.amount;
        boolean dateDiffers = date != null && !date.equals(out.dueDate);
        TransactionsService.clearReceiptScan(out);
        if (totalDiffers || dateDiffers) {
            out.receiptScanStatus = "DIVERGENT";
            out.receiptScanTotal = totalDiffers ? total : null;
            out.receiptScanDate = dateDiffers ? date : null;
        }
        return out;
    }

    // ---------- Chamadas ao servidor ----------

    public static ReceiptScan scanReceipt(byte[] image, String filename, String mime, String ignoreTransactionId) {
        ReceiptRead read = api().upload("/api/receipts/scan", image, filename != null ? filename : "comprovante.jpg",
            mime != null ? mime : "image/jpeg", ReceiptRead.class);
        return toScanDto(read, ignoreTransactionId);
    }

    public static ReceiptScan receiptJob(long jobId, String ignoreTransactionId) {
        ReceiptRead read = api().get("/api/receipts/jobs/" + jobId, ReceiptRead.class);
        return toScanDto(read, ignoreTransactionId);
    }

    private static TransactionRec withScanStatus(TransactionRec t, String status) {
        TransactionRec next = t.copy();
        next.receiptScanStatus = status;
        next.receiptScanTotal = null;
        next.receiptScanDate = null;
        return next;
    }

    /** Aplica no lançamento o estado da leitura ligada à foto dele. */
    private static void applyResult(TransactionRec t, JobResult res) {
        LocalStore s = Ctx.db();
        if (res == null) {
            // Nenhuma leitura para a foto (provedor direto, ou foto sem leitura): nada a esperar.
            if ("PENDING".equals(t.receiptScanStatus)) {
                TransactionRec next = t.copy();
                next.receiptScanStatus = null;
                s.put(Entity.TRANSACTION, next);
            }
            return;
        }
        if ("PENDING".equals(res.status)) {
            if (!"PENDING".equals(t.receiptScanStatus)) {
                s.put(Entity.TRANSACTION, withScanStatus(t, "PENDING"));
            }
        } else if ("FAILED".equals(res.status)) {
            if (!"FAILED".equals(t.receiptScanStatus)) {
                s.put(Entity.TRANSACTION, withScanStatus(t, "FAILED"));
            }
        } else if (res.scan != null) {
            s.put(Entity.TRANSACTION, applyScan(t, res.scan, res.qrAccessKey));
        } else if ("PENDING".equals(t.receiptScanStatus)) {
            // Pronta e já vista pelo formulário quando o lançamento foi salvo.
            TransactionRec next = t.copy();
            next.receiptScanStatus = null;
            s.put(Entity.TRANSACTION, next);
        }
    }

    private static Map<String, String> link(TransactionRec t) {
        Map<String, String> body = new HashMap<>();
        body.put("receiptFile", t.receiptFile);
        body.put("transactionId", t.id);
        return body;
    }

    private static JobResult attach(TransactionRec t) {
        return api().post("/api/receipts/attach", link(t), JobResult.class);
    }

    /**
     * O lançamento acabou de ser salvo com uma foto nova: liga a leitura dela a ele. Sem rede, fica marcado como
     * pendente e é ligado na próxima sincronização ({@link #processPendingReceipts()}).
     */
    public static void attachReceipt(String transactionId) {
        TransactionRec t = Ctx.db().get(Entity.TRANSACTION, transactionId);
        if (t == null || !Text.present(t.receiptFile)) {
            return;
        }
        try {
            applyResult(t, attach(t));
        } catch (ApiException e) {
            if (e.status == 0) {
                Ctx.db().put(Entity.TRANSACTION, withScanStatus(t, "PENDING"));
            } else {
                throw e;
            }
        }
    }

    /** Depois de cada sincronização: confere as leituras pendentes (de qualquer aparelho) e aplica as que terminaram. */
    public static void processPendingReceipts() {
        LocalStore s = Ctx.db();
        List<TransactionRec> pending = new ArrayList<>();
        for (TransactionRec t : s.list(Entity.TRANSACTION)) {
            if ("PENDING".equals(t.receiptScanStatus) && Text.present(t.receiptFile)) {
                pending.add(t);
            }
        }
        for (TransactionRec t : pending) {
            try {
                JobResult res = attach(t);
                TransactionRec current = s.get(Entity.TRANSACTION, t.id);
                if (current != null) {
                    applyResult(current, res);
                }
            } catch (RuntimeException e) {
                return;
            }
        }
        if (!pending.isEmpty()) {
            s.flush();
        }
    }

    /** "Ler de novo": a foto do lançamento volta para a fila do worker. */
    public static Transaction retryReceipt(String transactionId) {
        TransactionRec t = TransactionsService.requireTransaction(transactionId);
        if (!Text.present(t.receiptFile)) {
            throw new Ctx.BusinessException("Este lançamento não tem comprovante para ler");
        }
        api().post("/api/receipts/retry", link(t), JobResult.class);
        TransactionRec next = withScanStatus(t, "PENDING");
        Ctx.db().put(Entity.TRANSACTION, next);
        return Mappers.transactionDto(next, true, null);
    }

    private static String encode(String file) {
        try {
            return URLEncoder.encode(file, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Bytes da foto guardada no servidor, ou null se ela não existe (404). */
    public static byte[] downloadReceipt(String file) {
        try {
            return api().download("/api/receipts/" + encode(file));
        } catch (ApiException e) {
            if (e.status == 404) {
                return null;
            }
            throw e;
        }
    }

    /** Sobe a foto e devolve o nome com que o servidor a guardou. */
    public static String uploadReceipt(byte[] bytes, String originalName) {
        UploadResult r = api().upload("/api/receipts", bytes, originalName, "image/jpeg", UploadResult.class);
        return r != null ? r.receiptFile : null;
    }

    /** Acesso às fotos para a cópia de segurança. */
    public static BackupService.ReceiptIo io() {
        return new BackupService.ReceiptIo() {
            @Override
            public byte[] downloadReceipt(String file) {
                return ReceiptsService.downloadReceipt(file);
            }

            @Override
            public String uploadReceipt(byte[] bytes, String originalName) {
                return ReceiptsService.uploadReceipt(bytes, originalName);
            }
        };
    }
}
