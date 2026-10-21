package br.com.contaslt.transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import br.com.contaslt.account.Account;
import br.com.contaslt.category.Category;
import br.com.contaslt.common.TransactionType;
import br.com.contaslt.imports.ImportBatch;
import br.com.contaslt.recurring.RecurringBill;
import br.com.contaslt.transfer.Transfer;
import br.com.contaslt.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.CascadeType;

import org.hibernate.annotations.BatchSize;

@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id")
    private Account account;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recurring_bill_id")
    private RecurringBill recurringBill;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "import_batch_id")
    private ImportBatch importBatch;

    /** Preenchido quando o lançamento é uma perna de transferência entre contas. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transfer_id")
    private Transfer transfer;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(nullable = false)
    private boolean paid;

    private String notes;

    /** Identificador externo (FITID do OFX) usado para evitar duplicidade na importação. */
    @Column(name = "external_id")
    private String externalId;

    @Column(name = "installment_number")
    private Integer installmentNumber;

    /** Estabelecimento lido do comprovante. */
    private String merchant;

    /** Nome do arquivo de imagem do comprovante no armazenamento local. */
    @Column(name = "receipt_file")
    private String receiptFile;

    /** Chave de acesso da NFC-e (44 dígitos), usada para evitar lançar o mesmo cupom duas vezes. */
    @Column(name = "receipt_access_key")
    private String receiptAccessKey;

    /** TRUE quando o lançamento já existia e foi vinculado a uma linha de extrato importado. */
    @Column(name = "linked_import", nullable = false)
    private boolean linkedImport;

    @OneToMany(mappedBy = "transaction", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position asc")
    @BatchSize(size = 50)
    private List<TransactionItem> items = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public Account getAccount() { return account; }
    public void setAccount(Account account) { this.account = account; }
    public Category getCategory() { return category; }
    public void setCategory(Category category) { this.category = category; }
    public RecurringBill getRecurringBill() { return recurringBill; }
    public void setRecurringBill(RecurringBill recurringBill) { this.recurringBill = recurringBill; }
    public ImportBatch getImportBatch() { return importBatch; }
    public void setImportBatch(ImportBatch importBatch) { this.importBatch = importBatch; }
    public Transfer getTransfer() { return transfer; }
    public void setTransfer(Transfer transfer) { this.transfer = transfer; }
    public boolean isTransfer() { return transfer != null; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public TransactionType getType() { return type; }
    public void setType(TransactionType type) { this.type = type; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
    public boolean isPaid() { return paid; }
    public void setPaid(boolean paid) { this.paid = paid; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public Integer getInstallmentNumber() { return installmentNumber; }
    public void setInstallmentNumber(Integer installmentNumber) { this.installmentNumber = installmentNumber; }
    public String getMerchant() { return merchant; }
    public void setMerchant(String merchant) { this.merchant = merchant; }
    public String getReceiptFile() { return receiptFile; }
    public void setReceiptFile(String receiptFile) { this.receiptFile = receiptFile; }
    public String getReceiptAccessKey() { return receiptAccessKey; }
    public void setReceiptAccessKey(String receiptAccessKey) { this.receiptAccessKey = receiptAccessKey; }
    public boolean isLinkedImport() { return linkedImport; }
    public void setLinkedImport(boolean linkedImport) { this.linkedImport = linkedImport; }
    public List<TransactionItem> getItems() { return items; }

    /** Substitui os itens, mantendo a ordem informada. */
    public void replaceItems(List<TransactionItem> newItems) {
        items.clear();
        int position = 0;
        for (TransactionItem item : newItems) {
            item.setTransaction(this);
            item.setPosition(position++);
            items.add(item);
        }
    }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
