package br.com.contaslt.recurring;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import br.com.contaslt.recurring.RecurringBillDtos.RecurringBillDto;
import br.com.contaslt.recurring.RecurringBillDtos.RecurringBillRequest;
import br.com.contaslt.user.CurrentUser;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/recurring-bills")
public class RecurringBillController {

    private final RecurringBillService bills;
    private final CurrentUser currentUser;

    public RecurringBillController(RecurringBillService bills, CurrentUser currentUser) {
        this.bills = bills;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<RecurringBillDto> list() {
        return bills.list(currentUser.id());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RecurringBillDto create(@Valid @RequestBody RecurringBillRequest req) {
        return bills.create(currentUser.get(), req);
    }

    @PutMapping("/{id}")
    public RecurringBillDto update(@PathVariable Long id, @Valid @RequestBody RecurringBillRequest req) {
        return bills.update(currentUser.get(), id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        bills.delete(currentUser.id(), id);
    }
}
