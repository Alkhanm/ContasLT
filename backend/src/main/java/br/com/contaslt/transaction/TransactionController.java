package br.com.contaslt.transaction;

import java.time.YearMonth;

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

import br.com.contaslt.common.TransactionType;
import br.com.contaslt.transaction.TransactionDtos.PaidRequest;
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
    private final CurrentUser currentUser;

    public TransactionController(TransactionService transactions, CurrentUser currentUser) {
        this.transactions = transactions;
        this.currentUser = currentUser;
    }

    @GetMapping
    public TransactionPage list(
            @RequestParam(required = false) String month,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) Boolean paid,
            @RequestParam(required = false) String search) {
        YearMonth ym = month == null || month.isBlank() ? YearMonth.now() : YearMonth.parse(month);
        return transactions.list(currentUser.get(), new Filter(ym, accountId, categoryId, type, paid, search));
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

    @PutMapping("/{id}")
    public TransactionDto update(@PathVariable Long id, @Valid @RequestBody TransactionRequest req) {
        return transactions.update(currentUser.id(), id, req);
    }

    @PatchMapping("/{id}/paid")
    public TransactionDto setPaid(@PathVariable Long id, @Valid @RequestBody PaidRequest req) {
        return transactions.setPaid(currentUser.id(), id, req.paid());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        transactions.delete(currentUser.id(), id);
    }
}
