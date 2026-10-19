package br.com.contaslt.transfer;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import br.com.contaslt.transfer.TransferDtos.LinkRequest;
import br.com.contaslt.transfer.TransferDtos.SuggestionList;
import br.com.contaslt.transfer.TransferDtos.TransferDto;
import br.com.contaslt.transfer.TransferDtos.TransferRequest;
import br.com.contaslt.user.CurrentUser;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final TransferService transfers;
    private final CurrentUser currentUser;

    public TransferController(TransferService transfers, CurrentUser currentUser) {
        this.transfers = transfers;
        this.currentUser = currentUser;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransferDto create(@Valid @RequestBody TransferRequest req) {
        return transfers.create(currentUser.get(), req);
    }

    @PostMapping("/link")
    @ResponseStatus(HttpStatus.CREATED)
    public TransferDto link(@Valid @RequestBody LinkRequest req) {
        return transfers.link(currentUser.get(), req);
    }

    @GetMapping("/suggestions")
    public SuggestionList suggestions() {
        return new SuggestionList(transfers.suggestions(currentUser.get()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlink(@PathVariable Long id) {
        transfers.unlink(currentUser.id(), id);
    }
}
