package br.com.contaslt.card;

import java.time.YearMonth;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.com.contaslt.card.CardInvoiceDtos.CardInvoiceDto;
import br.com.contaslt.user.CurrentUser;

@RestController
@RequestMapping("/api/accounts/{id}/invoices")
public class CardInvoiceController {

    private final CardInvoiceService invoices;
    private final CurrentUser currentUser;

    public CardInvoiceController(CardInvoiceService invoices, CurrentUser currentUser) {
        this.invoices = invoices;
        this.currentUser = currentUser;
    }

    /** Resumo da fatura do cartão que vence no mês informado. */
    @GetMapping
    public CardInvoiceDto get(@PathVariable Long id, @RequestParam YearMonth month) {
        return invoices.invoice(currentUser.get(), id, month);
    }
}
