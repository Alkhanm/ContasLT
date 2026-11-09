package br.com.contaslt.ui;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

import android.app.Activity;
import android.content.Context;
import android.os.CancellationSignal;

import androidx.core.content.ContextCompat;
import androidx.credentials.ClearCredentialStateRequest;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.ClearCredentialException;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.NoCredentialException;

import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;

import br.com.contaslt.BuildConfig;

/**
 * Login com Google pelo Credential Manager: abre a escolha de conta do Google e devolve o ID token, que o backend
 * confere em /api/auth/google (o mesmo token do botão do PWA; {@code aud} = ID do cliente web).
 */
public final class GoogleSignIn {

    private GoogleSignIn() {
    }

    public static boolean available() {
        return !BuildConfig.GOOGLE_WEB_CLIENT_ID.isEmpty();
    }

    /** {@code onError} recebe a mensagem já pronta para mostrar; desistir da escolha de conta não chama nada. */
    public static void requestIdToken(Activity activity, Consumer<String> onToken, Consumer<String> onError) {
        GetSignInWithGoogleOption option = new GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build();
        GetCredentialRequest request = new GetCredentialRequest.Builder().addCredentialOption(option).build();
        Executor main = ContextCompat.getMainExecutor(activity);
        CredentialManager.create(activity).getCredentialAsync(activity, request, new CancellationSignal(), main,
            new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                @Override
                public void onResult(GetCredentialResponse result) {
                    Credential c = result.getCredential();
                    if (c instanceof CustomCredential cc
                            && GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(cc.getType())) {
                        try {
                            onToken.accept(GoogleIdTokenCredential.createFrom(cc.getData()).getIdToken());
                        } catch (RuntimeException e) {
                            onError.accept("Não foi possível ler a conta do Google. Tente de novo.");
                        }
                    } else {
                        onError.accept("Resposta inesperada do Google. Tente de novo.");
                    }
                }

                @Override
                public void onError(GetCredentialException e) {
                    if (e instanceof GetCredentialCancellationException) {
                        return;
                    }
                    onError.accept(e instanceof NoCredentialException
                        ? "Nenhuma conta Google neste aparelho. Adicione uma nas configurações do Android."
                        : "Não foi possível entrar com o Google. Tente de novo.");
                }
            });
    }

    /** Ao sair: o Google não escolhe a mesma conta sozinho no próximo login. */
    public static void signOut(Context context) {
        if (!available()) {
            return;
        }
        CredentialManager.create(context).clearCredentialStateAsync(new ClearCredentialStateRequest(), null,
            Runnable::run, new CredentialManagerCallback<Void, ClearCredentialException>() {
                @Override
                public void onResult(Void result) {
                }

                @Override
                public void onError(ClearCredentialException e) {
                }
            });
    }
}
