package br.com.contaslt.receipt;

import br.com.contaslt.common.BusinessException;

/** O serviço de visão não respondeu (fora do ar, sem rede): vale tentar de novo mais tarde, sem contar como falha da foto. */
public class ScannerUnavailableException extends BusinessException {

    public ScannerUnavailableException(String message) {
        super(message);
    }
}
