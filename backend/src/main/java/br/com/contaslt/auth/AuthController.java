package br.com.contaslt.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import br.com.contaslt.auth.AuthDtos.AuthResponse;
import br.com.contaslt.auth.AuthDtos.LoginRequest;
import br.com.contaslt.auth.AuthDtos.RegisterRequest;
import br.com.contaslt.auth.AuthDtos.UserDto;
import br.com.contaslt.user.CurrentUser;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;
    private final CurrentUser currentUser;

    public AuthController(AuthService auth, CurrentUser currentUser) {
        this.auth = auth;
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

    /** Exclui a conta e todos os dados (confirmação por e-mail e senha; usado pelo app e pela página web). */
    @PostMapping("/delete-account")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAccount(@Valid @RequestBody br.com.contaslt.auth.AuthDtos.DeleteAccountRequest req) {
        auth.deleteAccount(req);
    }

    @GetMapping("/me")
    public UserDto me() {
        return AuthService.toDto(currentUser.load());
    }
}
