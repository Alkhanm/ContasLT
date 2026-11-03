package br.com.contaslt.account;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.contaslt.account.AccountDtos.AccountDto;
import br.com.contaslt.account.AccountDtos.AccountRequest;
import br.com.contaslt.card.InvoicePeriod;
import br.com.contaslt.common.BusinessException;
import br.com.contaslt.common.NotFoundException;
import br.com.contaslt.common.TransactionType;
import br.com.contaslt.transaction.Transaction;
import br.com.contaslt.transaction.TransactionRepository;
import br.com.contaslt.user.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final EntityManager em;

    public AccountService(AccountRepository accounts, TransactionRepository transactions, EntityManager em) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.em = em;
    }

    @Transactional(readOnly = true)
    public List<AccountDto> list(Long userId, boolean includeArchived) {
        List<Account> all = accounts.findByUserIdOrderByArchivedAscNameAsc(userId);
        // Uma consulta para os movimentos pagos de todas as contas, em vez de uma por conta.
        Map<Long, BigDecimal> movements = new HashMap<>();
        for (Object[] row : accounts.sumPaidMovementsByAccount(userId)) {
            movements.put((Long) row[0], (BigDecimal) row[1]);
        }
        List<AccountDto> result = new ArrayList<>();
        for (Account a : all) {
            if (includeArchived || !a.isArchived()) {
                result.add(toDto(a, movements.getOrDefault(a.getId(), BigDecimal.ZERO)));
            }
        }
        return result;
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
        return toDto(a, a.isCreditCard() ? BigDecimal.ZERO : accounts.sumPaidMovements(a.getId()));
    }

    private AccountDto toDto(Account a, BigDecimal paidMovements) {
        BigDecimal balance = a.isCreditCard() ? a.getInitialBalance().subtract(openDebt(a))
            : a.getInitialBalance().add(paidMovements);
        return new AccountDto(a.getId(), a.getName(), a.getType(), a.getInitialBalance(), a.getColor(),
            a.isArchived(), balance, a.getClosingDay(), a.getDueDay());
    }

    /**
     * Saldo devedor do cartão: compras (líquidas) fora dos períodos das faturas que já têm pagamento registrado.
     * Duas consultas por cartão (meses pagos + soma), em vez de uma por fatura paga.
     */
    private BigDecimal openDebt(Account card) {
        List<LocalDate> paidMonths = transactions.findPaidInvoiceMonths(card.getId());
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<BigDecimal> q = cb.createQuery(BigDecimal.class);
        Root<Transaction> t = q.from(Transaction.class);
        List<Predicate> where = new ArrayList<>();
        where.add(cb.equal(t.get("account").get("id"), card.getId()));
        where.add(cb.isNull(t.get("transfer")));
        for (LocalDate month : paidMonths) {
            InvoicePeriod p = InvoicePeriod.of(card, YearMonth.from(month));
            where.add(cb.not(cb.between(t.<LocalDate>get("dueDate"), p.start(), p.end())));
        }
        q.select(cb.coalesce(cb.sum(cb.<BigDecimal>selectCase()
            .when(cb.equal(t.get("type"), TransactionType.EXPENSE), t.<BigDecimal>get("amount"))
            .otherwise(cb.neg(t.<BigDecimal>get("amount")))), BigDecimal.ZERO))
            .where(where.toArray(Predicate[]::new));
        return em.createQuery(q).getSingleResult();
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
