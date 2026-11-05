package br.com.contaslt;

import android.content.Context;

import br.com.contaslt.data.Api;
import br.com.contaslt.data.Auth;
import br.com.contaslt.data.Prefs;
import br.com.contaslt.data.Session;

/** Dependências do app (sem framework de injeção). */
public class AppContainer {

    public final Context context;
    public final Prefs prefs;
    public final Api api;
    public final Session session;
    public final Auth auth;

    public AppContainer(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = new Prefs(this.context);
        this.api = new Api(prefs);
        this.session = new Session(this.context, api);
        this.auth = new Auth(this);
    }
}
