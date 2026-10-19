package br.com.contaslt.transfer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.contaslt.account.Account;
import br.com.contaslt.account.AccountService;
import br.com.contaslt.common.BusinessException;
import br.com.contaslt.common.NotFoundException;
import br.com.contaslt.common.TextNormalizer;
import br.com.contaslt.common.TransactionType;
import br.com.contaslt.transaction.Transaction;
import br.com.contaslt.transaction.TransactionMapper;
import br.com.contaslt.transaction.TransactionRepository;
import br.com.contaslt.transaction.TransactionService;
import br.com.contaslt.transfer.TransferDtos.LinkRequest;
import br.com.contaslt.transfer.TransferDtos.Suggestion;
import br.com.contaslt.transfer.TransferDtos.TransferDto;
import br.com.contaslt.transfer.TransferDtos.TransferRequest;
import br.com.contaslt.user.User;

/**
 * Transferências entre contas do mesmo usuário. Cada transferência é um par de lançamentos
 * (despesa na origem, receita no destino) vinculados; eles continuam valendo para o saldo de cada conta,
 * mas são ignorados em receitas, despesas e categorias, por serem só movimentação interna.
 */
@Service
public class TransferService {

    /** Janela máxima entre a saída e a entrada para o cruzamento automático sugerir o par. */
    static final int MAX_DAYS_APART = 3;
    /** Quanto do histórico é vasculhado em busca de pares. */
    private static final int LOOKBACK_MONTHS = 18;
    private static final List<String> TRANSFER_WORDS = List.of(
        "transf", "pix", "ted", "doc ", "aplicacao", "resgate", "envio", "enviad", "recebid", "entre contas");

    private final TransferRepository transfers;
    private final TransactionRepository transactions;
    private final TransactionService transactionService;
    private final AccountService accounts;

    public TransferService(TransferRepository transfers, TransactionRepository transactions,
            TransactionService transactionService, AccountService accounts) {
        this.transfers = transfers;
        this.transactions = transactions;
        this.transactionService = transactionService;
        this.accounts = accounts;
    }

    @Transactional
    public TransferDto create(User user, TransferRequest req) {
        if (req.fromAccountId().equals(req.toAccountId())) {
            throw new BusinessException("Origem e destino precisam ser contas diferentes");
        }
        Account from = accounts.require(user.getId(), req.fromAccountId());
        Account to = accounts.require(user.getId(), req.toAccountId());
        String description = req.description() == null || req.description().isBlank()
            ? "Transferência" : req.description().trim();
        boolean paid = req.paid() == null || req.paid();

        Transaction out = leg(user, from, TransactionType.EXPENSE, req.amount(), req.date(), description, paid);
        Transaction in = leg(user, to, TransactionType.INCOME, req.amount(), req.date(), description, paid);
        return toDto(link(user, out, in));
    }

    /** Vincula dois lançamentos existentes; a ordem informada não importa, a despesa vira a origem. */
    @Transactional
    public TransferDto link(User user, LinkRequest req) {
        Transaction a = transactionService.require(user.getId(), req.transactionId());
        Transaction b = transactionService.require(user.getId(), req.otherTransactionId());
        if (a.getId().equals(b.getId())) {
            throw new BusinessException("Escolha dois lançamentos diferentes");
        }
        if (a.getType() == b.getType()) {
            throw new BusinessException("Uma transferência precisa de uma saída (despesa) e uma entrada (receita)");
        }
        Transaction out = a.getType() == TransactionType.EXPENSE ? a : b;
        Transaction in = out == a ? b : a;
        validatePair(out, in);
        return toDto(link(user, out, in));
    }

    /** Desfaz o vínculo; os dois lançamentos continuam existindo como despesa e receita comuns. */
    @Transactional
    public void unlink(Long userId, Long transferId) {
        Transfer t = transfers.findByIdAndUserId(transferId, userId)
            .orElseThrow(() -> new NotFoundException("Transferência não encontrada"));
        t.getFromTransaction().setTransfer(null);
        t.getToTransaction().setTransfer(null);
        transfers.delete(t);
    }

    /**
     * Cruzamento automático: procura despesas e receitas de mesmo valor, em contas diferentes, com até
     * {@link #MAX_DAYS_APART} dias de distância. Cada lançamento entra em no máximo um par; em caso de
     * empate, vence o par com datas mais próximas e descrição mais parecida com uma transferência.
     */
    @Transactional(readOnly = true)
    public List<Suggestion> suggestions(User user) {
        List<Transaction> pool = transactions.findUnlinkedSince(user.getId(),
            LocalDate.now().minusMonths(LOOKBACK_MONTHS));
        List<Transaction> outs = pool.stream().filter(t -> t.getType() == TransactionType.EXPENSE).toList();
        List<Transaction> ins = pool.stream().filter(t -> t.getType() == TransactionType.INCOME).toList();
        String userName = TextNormalizer.normalize(user.getName());

        record Candidate(Transaction out, Transaction in, int days, int score) {
        }
        List<Candidate> candidates = new ArrayList<>();
        for (Transaction out : outs) {
            for (Transaction in : ins) {
                if (out.getAccount().getId().equals(in.getAccount().getId())
                        || out.getAmount().compareTo(in.getAmount()) != 0) {
                    continue;
                }
                int days = (int) Math.abs(ChronoUnit.DAYS.between(out.getDueDate(), in.getDueDate()));
                if (days > MAX_DAYS_APART) {
                    continue;
                }
                int score = 100 - days * 15
                    + hint(out, in.getAccount(), userName) + hint(in, out.getAccount(), userName)
                    + (out.isPaid() == in.isPaid() ? 5 : 0);
                candidates.add(new Candidate(out, in, days, score));
            }
        }
        candidates.sort(Comparator.comparingInt(Candidate::score).reversed()
            .thenComparing(c -> c.out().getDueDate())
            .thenComparing(c -> c.out().getId()));

        Set<Long> used = new HashSet<>();
        List<Suggestion> result = new ArrayList<>();
        for (Candidate c : candidates) {
            if (used.contains(c.out().getId()) || used.contains(c.in().getId())) {
                continue;
            }
            used.add(c.out().getId());
            used.add(c.in().getId());
            result.add(new Suggestion(TransactionMapper.toDto(c.out()), TransactionMapper.toDto(c.in()),
                c.days(), c.score()));
        }
        result.sort(Comparator.comparing((Suggestion s) -> s.from().dueDate()).reversed());
        return result;
    }

    /** Pontos extras quando a descrição parece uma transferência ou cita a outra conta / o próprio usuário. */
    private static int hint(Transaction t, Account other, String userName) {
        String d = TextNormalizer.normalize(t.getDescription()).toLowerCase(Locale.ROOT);
        int score = 0;
        if (TRANSFER_WORDS.stream().anyMatch(d::contains)) {
            score += 20;
        }
        String otherName = TextNormalizer.normalize(other.getName()).toLowerCase(Locale.ROOT);
        if (otherName.length() >= 3 && d.contains(otherName)) {
            score += 15;
        }
        if (userName != null && userName.length() >= 3 && d.contains(userName.toLowerCase(Locale.ROOT))) {
            score += 10;
        }
        return score;
    }

    private static void validatePair(Transaction out, Transaction in) {
        if (out.isTransfer() || in.isTransfer()) {
            throw new BusinessException("Um dos lançamentos já faz parte de outra transferência");
        }
        if (out.getAccount().getId().equals(in.getAccount().getId())) {
            throw new BusinessException("A saída e a entrada precisam estar em contas diferentes");
        }
        if (out.getAmount().compareTo(in.getAmount()) != 0) {
            throw new BusinessException("Os dois lançamentos precisam ter o mesmo valor");
        }
    }

    private Transfer link(User user, Transaction out, Transaction in) {
        Transfer t = new Transfer();
        t.setUser(user);
        t.setFromTransaction(out);
        t.setToTransaction(in);
        t = transfers.save(t);
        // Transferência não é despesa nem receita: perde a categoria para não poluir os relatórios.
        out.setTransfer(t);
        out.setCategory(null);
        in.setTransfer(t);
        in.setCategory(null);
        return t;
    }

    private Transaction leg(User user, Account account, TransactionType type, BigDecimal amount, LocalDate date,
            String description, boolean paid) {
        Transaction t = new Transaction();
        t.setUser(user);
        t.setAccount(account);
        t.setType(type);
        t.setAmount(amount);
        t.setDueDate(date);
        t.setDescription(description);
        t.setPaid(paid);
        return transactions.save(t);
    }

    private static TransferDto toDto(Transfer t) {
        return new TransferDto(t.getId(), TransactionMapper.toDto(t.getFromTransaction()),
            TransactionMapper.toDto(t.getToTransaction()));
    }
}
