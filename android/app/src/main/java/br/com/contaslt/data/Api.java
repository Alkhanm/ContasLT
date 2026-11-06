package br.com.contaslt.data;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Cliente HTTP do servidor (porte de frontend/src/api/client.ts): token no header, erros RFC 9457 viram
 * {@link ApiException} com a mensagem para o usuário. Chamadas bloqueantes: use fora da thread principal.
 */
public class Api implements SyncTransport {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final Prefs prefs;
    private final OkHttpClient http;
    /** Avisado em 401 (sessão expirada). */
    public volatile Consumer<ApiException> onUnauthorized;

    public Api(Prefs prefs) {
        this.prefs = prefs;
        this.http = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build();
    }

    public <T> T get(String path, Type type) {
        return send(new Request.Builder().url(url(path)).get(), path, type);
    }

    public <T> T post(String path, Object body, Type type) {
        RequestBody rb = RequestBody.create(body == null ? "{}" : Json.GSON.toJson(body), JSON);
        return send(new Request.Builder().url(url(path)).post(rb), path, type);
    }

    /** Envia um arquivo (multipart, campo "file"). */
    public <T> T upload(String path, byte[] bytes, String filename, String mime, Type type) {
        RequestBody file = RequestBody.create(bytes, MediaType.get(mime));
        MultipartBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", filename, file).build();
        return send(new Request.Builder().url(url(path)).post(body), path, type);
    }

    /** Baixa um recurso binário autenticado (foto de comprovante). Null se não existir. */
    public byte[] download(String path) {
        Request.Builder rb = new Request.Builder().url(url(path)).get();
        auth(rb);
        try (Response res = http.newCall(rb.build()).execute()) {
            if (res.code() == 404) {
                return null;
            }
            if (!res.isSuccessful()) {
                throw error(res.code(), res.body().string(), path);
            }
            return res.body().bytes();
        } catch (IOException e) {
            throw offline();
        }
    }

    private <T> T send(Request.Builder rb, String path, Type type) {
        auth(rb);
        rb.header("Accept", "application/json");
        try (Response res = http.newCall(rb.build()).execute()) {
            String text = res.body().string();
            if (!res.isSuccessful()) {
                throw error(res.code(), text, path);
            }
            if (res.code() == 204 || text.isEmpty() || type == null) {
                return null;
            }
            return Json.GSON.fromJson(text, type);
        } catch (IOException e) {
            throw offline();
        }
    }

    private void auth(Request.Builder rb) {
        String token = prefs.token();
        if (token != null) {
            rb.header("Authorization", "Bearer " + token);
        }
    }

    private String url(String path) {
        return prefs.server() + path;
    }

    private static ApiException offline() {
        return new ApiException(0, "Sem conexão com o servidor. Verifique sua internet e tente de novo.");
    }

    private ApiException error(int status, String text, String path) {
        if (status == 401 && !path.startsWith("/api/auth/login") && !path.startsWith("/api/auth/delete-account")) {
            ApiException e = new ApiException(401, "Sua sessão expirou. Entre novamente.");
            Consumer<ApiException> cb = onUnauthorized;
            if (cb != null) {
                cb.accept(e);
            }
            return e;
        }
        String detail = null;
        Map<String, String> errors = new HashMap<>();
        try {
            JsonElement el = JsonParser.parseString(text);
            if (el.isJsonObject()) {
                JsonObject o = el.getAsJsonObject();
                if (o.has("detail") && !o.get("detail").isJsonNull()) {
                    detail = o.get("detail").getAsString();
                }
                if (o.has("errors") && o.get("errors").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> en : o.getAsJsonObject("errors").entrySet()) {
                        errors.put(en.getKey(), en.getValue().getAsString());
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // corpo não é JSON
        }
        return new ApiException(status, detail != null ? detail : "Algo deu errado", errors);
    }

    // ---------- Sincronização ----------

    @Override
    public PullResponse pull(long since, int limit) {
        return get("/api/sync/pull?since=" + since + "&limit=" + limit, PullResponse.class);
    }

    @Override
    public PushResponse push(List<Change> changes) {
        return post("/api/sync/push", java.util.Collections.singletonMap("changes", changes), PushResponse.class);
    }
}
