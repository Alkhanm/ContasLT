package br.com.contaslt.data;

import android.content.Context;
import android.content.SharedPreferences;

import br.com.contaslt.BuildConfig;
import br.com.contaslt.dto.User;

/** Token, usuário e servidor guardados no aparelho (o app abre offline direto nos dados locais). */
public class Prefs {

    private final SharedPreferences sp;

    public Prefs(Context context) {
        sp = context.getSharedPreferences("contaslt", Context.MODE_PRIVATE);
    }

    public String server() {
        return sp.getString("server", BuildConfig.DEFAULT_SERVER);
    }

    public void setServer(String url) {
        sp.edit().putString("server", url.replaceAll("/+$", "")).apply();
    }

    public String token() {
        return sp.getString("token", null);
    }

    public User user() {
        String raw = sp.getString("user", null);
        return raw == null ? null : Json.GSON.fromJson(raw, User.class);
    }

    public void setSession(String token, User user) {
        sp.edit().putString("token", token).putString("user", user == null ? null : Json.GSON.toJson(user)).apply();
    }

    public void clearSession() {
        sp.edit().remove("token").remove("user").apply();
    }

    /** "light", "dark" ou "system". */
    public String theme() {
        return sp.getString("theme", "system");
    }

    public void setTheme(String theme) {
        sp.edit().putString("theme", theme).apply();
    }

    public SharedPreferences raw() {
        return sp;
    }
}
