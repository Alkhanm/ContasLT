package br.com.contaslt.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/** JSON dos registros e da API. Nulos são escritos (o PWA lê os registros com todos os campos). */
public final class Json {

    public static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

    private Json() {
    }
}
