package br.com.contaslt.receipt;

import java.io.IOException;
import java.util.Optional;

/** Onde os bytes dos comprovantes ficam guardados (disco local ou Supabase Storage). */
interface ReceiptBlobStore {

    void put(String name, byte[] bytes) throws IOException;

    /** Vazio quando o arquivo não existe. */
    Optional<byte[]> get(String name) throws IOException;

    /** Apaga todos os arquivos cujo nome começa com o prefixo (exclusão de conta). Devolve quantos. */
    int deleteByPrefix(String prefix) throws IOException;
}
