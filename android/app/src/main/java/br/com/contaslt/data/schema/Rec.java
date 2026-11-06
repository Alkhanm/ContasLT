package br.com.contaslt.data.schema;

import br.com.contaslt.data.Json;

/**
 * Registro guardado no aparelho e sincronizado (`sync_records.data`). Os campos seguem exatamente
 * frontend/src/data/schema.ts: ids UUID, dinheiro em centavos (long), datas "AAAA-MM-DD". Campos públicos e
 * mutáveis, como os objetos do PWA; {@link #copy()} faz a cópia antes de alterar.
 */
public abstract class Rec {

    public String id;
    public String createdAt;

    @SuppressWarnings("unchecked")
    public <T extends Rec> T copy() {
        return (T) Json.GSON.fromJson(Json.GSON.toJson(this), getClass());
    }
}
