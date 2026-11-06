package br.com.contaslt.data.schema;

import java.util.List;

/** Tipos de registro sincronizados, com o nome usado no servidor (`sync_records.entity`). */
public final class Entity<T extends Rec> {

    public static final Entity<AccountRec> ACCOUNT = new Entity<>("account", AccountRec.class);
    public static final Entity<CategoryRec> CATEGORY = new Entity<>("category", CategoryRec.class);
    public static final Entity<CategoryRuleRec> CATEGORY_RULE = new Entity<>("categoryRule", CategoryRuleRec.class);
    public static final Entity<RecurringBillRec> RECURRING_BILL = new Entity<>("recurringBill", RecurringBillRec.class);
    public static final Entity<ImportBatchRec> IMPORT_BATCH = new Entity<>("importBatch", ImportBatchRec.class);
    public static final Entity<TransactionRec> TRANSACTION = new Entity<>("transaction", TransactionRec.class);
    public static final Entity<TransferRec> TRANSFER = new Entity<>("transfer", TransferRec.class);
    public static final Entity<TransferRejectionRec> TRANSFER_REJECTION = new Entity<>("transferRejection", TransferRejectionRec.class);

    public static final List<Entity<?>> ALL = java.util.Collections.unmodifiableList(java.util.Arrays.asList(ACCOUNT, CATEGORY, CATEGORY_RULE, RECURRING_BILL, IMPORT_BATCH,
        TRANSACTION, TRANSFER, TRANSFER_REJECTION));

    /**
     * updatedAt dos vencimentos de recorrente gerados automaticamente: qualquer edição real vence um vencimento
     * gerado em outro aparelho com o mesmo id.
     */
    public static final long GENERATED_AT = 1;

    public final String name;
    public final Class<T> type;

    private Entity(String name, Class<T> type) {
        this.name = name;
        this.type = type;
    }

    public static Entity<?> byName(String name) {
        for (Entity<?> e : ALL) {
            if (e.name.equals(name)) {
                return e;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return name;
    }
}
