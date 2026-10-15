package br.com.contaslt.account;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import br.com.contaslt.account.AccountDtos.AccountDto;
import br.com.contaslt.account.AccountDtos.AccountRequest;
import br.com.contaslt.user.CurrentUser;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accounts;
    private final CurrentUser currentUser;

    public AccountController(AccountService accounts, CurrentUser currentUser) {
        this.accounts = accounts;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<AccountDto> list(@RequestParam(defaultValue = "false") boolean includeArchived) {
        return accounts.list(currentUser.id(), includeArchived);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountDto create(@Valid @RequestBody AccountRequest req) {
        return accounts.create(currentUser.get(), req);
    }

    @PutMapping("/{id}")
    public AccountDto update(@PathVariable Long id, @Valid @RequestBody AccountRequest req) {
        return accounts.update(currentUser.id(), id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        accounts.delete(currentUser.id(), id);
    }
}
