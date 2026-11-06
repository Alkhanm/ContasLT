package br.com.contaslt.data;

/** Linha persistida (registros e outbox): o registro em JSON e o relógio da última gravação (epoch ms). */
public final class StoredRow {
    public final String id;
    public final String entity;
    /** JSON do registro; nulo quando excluído. */
    public final String data;
    public final boolean deleted;
    public final long updatedAt;

    public StoredRow(String id, String entity, String data, boolean deleted, long updatedAt) {
        this.id = id;
        this.entity = entity;
        this.data = data;
        this.deleted = deleted;
        this.updatedAt = updatedAt;
    }
}
