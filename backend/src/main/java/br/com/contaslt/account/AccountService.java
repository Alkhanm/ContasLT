package br.com.contaslt.account;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.contaslt.account.AccountDtos.AccountDto;
import br.com.contaslt.account.AccountDtos.AccountRequest;
import br.com.contaslt.card.InvoicePeriod;
import br.com.contaslt.common.BusinessException;
import br.com.contaslt.common.NotFoundException;
import br.com.contaslt.transaction.TransactionRepository;
import br.com.contaslt.user.User;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final TransactionRepository transactions;

    public AccountService(AccountRepository accounts, TransactionRepository transactions) {
        this.accounts = accounts;
        this.transactions = transactions;
    }

    @Transactional(readOnly = true)
    public List<AccountDto> list(Long userId, boolean includeArchived) {
        return accounts.findByUserIdOrderByArchivedAscNameAsc(userId).stream()
            .filter(a -> includeArchived || !a.isArchived())
            .map(this::toDto)
            .toList();
    }

    /** Entidades das contas do usuário (todas, inclusive arquivadas), para uso interno de outros serviços. */
    public List<Account> listEntities(Long userId) {
        return accounts.findByUserIdOrderByArchivedAscNameAsc(userId);
    }

    @Transactional
    public AccountDto create(User user, AccountRequest req) {
        Account account = new Account();
        account.setUser(user);
        apply(account, req);
        return toDto(accounts.save(account));
    }

    @Transactional
    public AccountDto update(Long userId, Long id, AccountRequest req) {
        Account account = require(userId, id);
        apply(account, req);
        return toDto(account);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        Account account = require(userId, id);
        if (transactions.existsByAccountId(id)) {
            throw new BusinessException("Esta conta possui lançamentos. Arquive-a em vez de excluir.");
        }
        accounts.delete(account);
    }

    public Account require(Long userId, Long id) {
        return accounts.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new NotFoundException("Conta não encontrada"));
    }

    @Transactional
    public void seedDefault(User user) {
        Account account = new Account();
        account.setUser(user);
        account.setName("Conta principal");
        account.setType(AccountType.CHECKING);
        account.setColor("#1B5E3F");
        accounts.save(account);
    }

    /**
     * Saldo da conta: saldo inicial + movimentos pagos. Para cartão de crédito é o saldo devedor em aberto (negativo):
     * compras registradas cuja fatura ainda não teve pagamento registrado.
     */
    public AccountDto toDto(Account a) {
        BigDecimal balance = a.isCreditCard() ? a.getInitialBalance().subtract(openDebt(a))
            : a.getInitialBalance().add(accounts.sumPaidMovements(a.getId()));
        return new AccountDto(a.getId(), a.getName(), a.getType(), a.getInitialBalance(), a.getColor(),
            a.isArchived(), balance, a.getClosingDay(), a.getDueDay());
    }

    private BigDecimal openDebt(Account card) {
        BigDecimal debt = transactions.sumCardPurchases(card.getId());
        for (LocalDate month : transactions.findPaidInvoiceMonths(card.getId())) {
            InvoicePeriod p = InvoicePeriod.of(card, YearMonth.from(month));
            debt = debt.subtract(transactions.sumCardPurchases(card.getId(), p.start(), p.end()));
        }
        return debt;
    }

    private static void apply(Account account, AccountRequest req) {
        account.setName(req.name().trim());
        account.setType(req.type());
        account.setInitialBalance(req.initialBalance() == null ? BigDecimal.ZERO : req.initialBalance());
        if (req.color() != null) {
            account.setColor(req.color());
        }
        if (req.archived() != null) {
            account.setArchived(req.archived());
        }
        if (req.type() == AccountType.CREDIT_CARD) {
            if ((req.closingDay() == null) != (req.dueDay() == null)) {
                throw new BusinessException("Informe o dia de fechamento e o de vencimento da fatura");
            }
            if (req.closingDay() != null && req.closingDay().equals(req.dueDay())) {
                throw new BusinessException("Fechamento e vencimento precisam ser dias diferentes");
            }
            account.setClosingDay(req.closingDay());
            account.setDueDay(req.dueDay());
        } else {
            account.setClosingDay(null);
            account.setDueDay(null);
        }
    }
}
