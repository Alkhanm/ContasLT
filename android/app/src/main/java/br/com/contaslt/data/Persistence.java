package br.com.contaslt.data;

import java.util.List;

/**
 * Onde o {@link LocalStore} guarda os dados: Room no app ({@code RoomPersistence}), memória nos testes.
 * Os métodos rodam fora da thread principal.
 */
public interface Persistence {

    List<StoredRow> loadAll();

    /** Grava os registros e os põe no outbox, numa transação. */
    void write(List<StoredRow> batch);

    /** Grava registros vindos do servidor e tira do outbox o que eles superaram (outbox.updatedAt <= remoto). */
    void applyRemote(List<StoredRow> accepted);

    /** Próximo lote do outbox, do mais antigo para o mais novo. */
    List<StoredRow> pendingBatch(int limit);

    /** Tira do outbox o que subiu sem ter mudado de novo enquanto subia (mesmo updatedAt). */
    void acknowledge(List<StoredRow> sent);

    int pendingCount();

    String getMeta(String key);

    void setMeta(String key, String value);

    void close();
}
