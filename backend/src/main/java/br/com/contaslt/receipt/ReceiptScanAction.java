package br.com.contaslt.receipt;

/** O que fazer com o aviso de leitura de comprovante de um lançamento. */
public enum ReceiptScanAction {
    /** Usar o total e a data lidos do cupom no lançamento. */
    ACCEPT,
    /** Manter o que foi lançado e tirar o aviso. */
    DISMISS,
    /** Enfileirar a foto de novo para o worker. */
    RETRY
}
