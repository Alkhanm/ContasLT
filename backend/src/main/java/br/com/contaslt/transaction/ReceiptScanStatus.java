package br.com.contaslt.transaction;

/** Leitura adiada do comprovante de um lançamento (ver {@code receipt_jobs}). Nulo = nada pendente. */
public enum ReceiptScanStatus {
    /** Foto guardada, esperando o worker do computador ler. */
    PENDING,
    /** Lido e aplicado, mas o total ou a data do cupom diferem do lançado: o usuário decide. */
    DIVERGENT,
    /** O modelo não conseguiu ler; pode tentar de novo. */
    FAILED
}
