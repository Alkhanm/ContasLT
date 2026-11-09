package br.com.contaslt.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import br.com.contaslt.auth.AuthDtos.AuthResponse;
import br.com.contaslt.auth.AuthDtos.ForgotPasswordRequest;
import br.com.contaslt.auth.AuthDtos.GoogleLoginRequest;
import br.com.contaslt.auth.AuthDtos.ResetPasswordRequest;
import br.com.contaslt.auth.AuthDtos.LoginRequest;
import br.com.contaslt.auth.AuthDtos.RegisterRequest;
import br.com.contaslt.auth.AuthDtos.UserDto;
import br.com.contaslt.user.CurrentUser;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;
    private final PasswordResetService passwordReset;
    private final CurrentUser currentUser;

    public AuthController(AuthService auth, PasswordResetService passwordReset, CurrentUser currentUser) {
        this.auth = auth;
        this.passwordReset = passwordReset;
        this.currentUser = currentUser;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest req) {
        return auth.register(req);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req) {
        return auth.login(req);
    }

    @PostMapping("/google")
    public AuthResponse google(@Valid @RequestBody GoogleLoginRequest req) {
        return auth.loginWithGoogle(req.idToken());
    }

    /** Manda o código de recuperação por e-mail. Sempre 204: não revela se o e-mail tem conta. */
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forgotPassword(@Valid @RequestBody ForgotPasswordRequest req) {
        passwordReset.requestCode(req.email());
    }

    /** Troca a senha com o código e já devolve a sessão. */
    @PostMapping("/reset-password")
    public AuthResponse resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        return auth.session(passwordReset.reset(req.email(), req.code(), req.password()));
    }

    /** Exclui a conta e todos os dados (confirmação por e-mail e senha ou pelo Google; usado pelo app e pela página web). */
    @PostMapping("/delete-account")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAccount(@RequestBody br.com.contaslt.auth.AuthDtos.DeleteAccountRequest req) {
        auth.deleteAccount(req);
    }

    @GetMapping("/me")
    public UserDto me() {
        return AuthService.toDto(currentUser.load());
    }
}
