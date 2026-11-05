package br.com.contaslt.services;

import static br.com.contaslt.services.Testing.cents;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;

import org.junit.Before;
import org.junit.Test;

import br.com.contaslt.data.schema.ItemRec;
import br.com.contaslt.data.schema.TransactionRec;
import br.com.contaslt.dto.ReceiptScan;
import br.com.contaslt.dto.Transaction;
import br.com.contaslt.dto.TransactionInput;
import br.com.contaslt.dto.TransactionItemInput;
import br.com.contaslt.services.ReceiptsService.RawItem;
import br.com.contaslt.services.ReceiptsService.RawScan;

/** Porte de frontend/src/services/receipts.test.ts (leitura adiada aplicada no lançamento já salvo). */
public class ReceiptsServiceTest {

    @Before
    public void setUp() {
        Testing.freshStore();
    }

    private static RawScan scan() {
        RawScan s = new RawScan();
        s.merchant = "SUPERMERCADO ALMEIDA";
        s.issueDate = "2026-10-25";
        s.dueDate = null;
        s.paid = true;
        s.total = 31.97;
        s.paymentMethod = "Pix";
        s.accessKey = null;
        s.items = new ArrayList<>(Arrays.asList(
            new RawItem("BATATA LAYS", 2.0, 10.99, 21.98),
            new RawItem("ENERGETICO", 1.0, 9.99, 9.99)));
        return s;
    }

    private static Transaction create(String description, double amount, String dueDate, String accountId,
                                      String merchant, TransactionItemInput... items) {
        TransactionInput in = Testing.tx(description, amount, "EXPENSE", dueDate, true, accountId, null);
        in.receiptFile = "u1-x.jpg";
        in.merchant = merchant;
        in.items = new ArrayList<>(Arrays.asList(items));
        return TransactionsService.createTransaction(in).transaction;
    }

    @Test
    public void itensEstabelecimentoECategoriaEntramTotalEDataDiferentesViramAviso() {
        Testing.Catalog c = Testing.basicCatalog();
        CatalogService.saveRule("almeida", c.expense.id);
        Transaction t = create("Mercado", 50, "2026-10-20", c.account.id, null);
        TransactionRec out = ReceiptsService.applyScan(TransactionsService.requireTransaction(t.id), scan(), null);
        assertEquals(2, out.items.size());
        ItemRec first = out.items.get(0);
        assertEquals("BATATA LAYS", first.description);
        assertEquals(Double.valueOf(2), first.quantity);
        assertEquals(Long.valueOf(1099), first.unitPrice);
        assertEquals(2198, first.total);
        assertEquals("SUPERMERCADO ALMEIDA", out.merchant);
        assertEquals(c.expense.id, out.categoryId);
        assertEquals(5000, out.amount);
        assertEquals("DIVERGENT", out.receiptScanStatus);
        assertEquals(Long.valueOf(3197), out.receiptScanTotal);
        assertEquals("2026-10-25", out.receiptScanDate);
        // O registro guardado não foi alterado (applyScan devolve uma cópia).
        assertTrue(TransactionsService.requireTransaction(t.id).items.isEmpty());
    }

    @Test
    public void mesmoTotalEDataSemAvisoEOQueOUsuarioPreencheuFica() {
        Testing.Catalog c = Testing.basicCatalog();
        TransactionItemInput feira = new TransactionItemInput();
        feira.description = "Feira";
        feira.total = cents(31.97);
        Transaction t = create("Mercado", 31.97, "2026-10-25", c.account.id, "Almeida", feira);
        TransactionRec saved = TransactionsService.requireTransaction(t.id).copy();
        saved.receiptScanStatus = "PENDING";
        TransactionRec out = ReceiptsService.applyScan(saved, scan(), null);
        assertNull(out.receiptScanStatus);
        assertEquals("Almeida", out.merchant);
        assertEquals(1, out.items.size());
    }

    @Test
    public void anoCorrigidoPelaChaveDeAcessoValida() {
        // UF 35, emissão 2610 (out/2026), CNPJ, modelo 65... dígito verificador calculado.
        String base = "3526101234567800019065001000000001123456789";
        int sum = 0;
        int w = 2;
        for (int i = 42; i >= 0; i--) {
            sum += (base.charAt(i) - '0') * w;
            w = w == 9 ? 2 : w + 1;
        }
        int dv = 11 - (sum % 11) >= 10 ? 0 : 11 - (sum % 11);
        String key = base + dv;
        assertTrue(ReceiptsService.isValidAccessKey(key));
        assertFalse(ReceiptsService.isValidAccessKey(base + ((dv + 1) % 10)));
        assertFalse(ReceiptsService.isValidAccessKey(null));
        assertEquals("2026-10-25", ReceiptsService.fixYear("2025-10-25", key));
        assertEquals("2025-09-25", ReceiptsService.fixYear("2025-09-25", key));
        assertEquals("2025-10-25", ReceiptsService.fixYear("2025-10-25", "123"));
        assertNull(ReceiptsService.fixYear(null, key));
    }

    @Test
    public void leituraProntaViraFormularioComDuplicataEVencimentoRecorrente() {
        Testing.Catalog c = Testing.basicCatalog();
        CatalogService.saveRule("almeida", c.expense.id);

        ReceiptsService.ReceiptRead read = new ReceiptsService.ReceiptRead();
        read.receiptFile = "u1-y.jpg";
        read.status = "DONE";
        read.workerOnline = true;
        read.scan = scan();
        ReceiptScan dto = ReceiptsService.toScanDto(read, null);
        assertEquals("2026-10-25", dto.date);
        assertEquals(Long.valueOf(3197), dto.total);
        assertEquals(3197, dto.itemsTotal);
        assertEquals(c.expense.id, dto.suggestedCategoryId);
        assertEquals(2, dto.items.size());
        assertEquals(Long.valueOf(1099), dto.items.get(0).unitPrice);
        assertNull(dto.duplicateOfTransactionId);

        // Mesmo dia e valor já lançados: provável duplicata (a não ser que seja o próprio lançamento em edição).
        Transaction t = create("Almeida", 31.97, "2026-10-25", c.account.id, null);
        assertEquals(t.id, ReceiptsService.toScanDto(read, null).duplicateOfTransactionId);
        assertNull(ReceiptsService.toScanDto(read, t.id).duplicateOfTransactionId);

        // Leitura ainda na fila: só o estado.
        read.status = "PENDING";
        read.jobId = 7L;
        ReceiptScan pending = ReceiptsService.toScanDto(read, null);
        assertEquals("PENDING", pending.status);
        assertEquals(Long.valueOf(7), pending.jobId);
        assertNull(pending.total);
        assertEquals("", pending.merchant);
    }
}
