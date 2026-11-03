package br.com.contaslt.transaction;

import java.time.YearMonth;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import br.com.contaslt.category.CategoryService;
import br.com.contaslt.common.TransactionType;
import br.com.contaslt.transaction.TransactionDtos.EditScope;
import br.com.contaslt.transaction.TransactionDtos.PaidRequest;
import br.com.contaslt.transaction.TransactionDtos.ReceiptScanRequest;
import br.com.contaslt.transaction.TransactionDtos.TransactionDto;
import br.com.contaslt.transaction.TransactionDtos.TransactionPage;
import br.com.contaslt.transaction.TransactionDtos.TransactionRequest;
import br.com.contaslt.transaction.TransactionService.Filter;
import br.com.contaslt.user.CurrentUser;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionService transactions;
    private final CategoryService categories;
    private final CurrentUser currentUser;

    public TransactionController(TransactionService transactions, CategoryService categories, CurrentUser currentUser) {
        this.transactions = transactions;
        this.categories = categories;
        this.currentUser = currentUser;
    }

    @GetMapping
    public TransactionPage list(
            @RequestParam(required = false) String month,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Boolean uncategorized,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) Boolean paid,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean transfers,
            @RequestParam(required = false) Boolean invoice) {
        YearMonth ym = month == null || month.isBlank() ? YearMonth.now() : YearMonth.parse(month);
        List<Long> categoryIds = categoryId == null ? null : categories.idsWithChildren(currentUser.id(), categoryId);
        return transactions.list(currentUser.get(),
            new Filter(ym, accountId, categoryIds, uncategorized, type, paid, search, transfers, invoice));
    }

    @GetMapping("/{id}")
    public TransactionDto get(@PathVariable Long id) {
        return transactions.get(currentUser.id(), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionDto create(@Valid @RequestBody TransactionRequest req) {
        return transactions.create(currentUser.get(), req);
    }

    /** {@code scope=FOLLOWING} em ocorrência de conta recorrente aplica a edição também aos próximos vencimentos. */
    @PutMapping("/{id}")
    public TransactionDto update(@PathVariable Long id, @Valid @RequestBody TransactionRequest req,
            @RequestParam(required = false, defaultValue = "THIS") EditScope scope) {
        return transactions.update(currentUser.get(), id, req, scope);
    }

    @PatchMapping("/{id}/paid")
    public TransactionDto setPaid(@PathVariable Long id, @Valid @RequestBody PaidRequest req) {
        return transactions.setPaid(currentUser.id(), id, req.paid());
    }

    /** Resposta ao aviso de leitura adiada do comprovante: usar o lido, manter o lançado ou ler de novo. */
    @PostMapping("/{id}/receipt-scan")
    public TransactionDto receiptScan(@PathVariable Long id, @Valid @RequestBody ReceiptScanRequest req) {
        return transactions.resolveReceiptScan(currentUser.get(), id, req.action());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        transactions.delete(currentUser.get(), id);
    }
}
