package br.com.contaslt.backup;

import java.io.IOException;
import java.time.LocalDate;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import br.com.contaslt.backup.BackupService.Imported;
import br.com.contaslt.common.BusinessException;
import br.com.contaslt.user.CurrentUser;
import br.com.contaslt.user.PremiumGuard;
import br.com.contaslt.user.User;
import jakarta.servlet.http.HttpServletResponse;

/** Exportar e importar os dados da conta (zip com JSON + fotos). Só para contas premium. */
@RestController
@RequestMapping("/api/backup")
public class BackupController {

    private final BackupService backups;
    private final CurrentUser currentUser;
    private final PremiumGuard premium;

    public BackupController(BackupService backups, CurrentUser currentUser, PremiumGuard premium) {
        this.backups = backups;
        this.currentUser = currentUser;
        this.premium = premium;
    }

    @GetMapping(value = "/export", produces = "application/zip")
    public void export(HttpServletResponse response) throws IOException {
        User user = currentUser.get();
        premium.require(user, "O backup não está liberado para a sua conta.");
        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", "attachment; filename=\"contaslt-dados-" + LocalDate.now() + ".zip\"");
        backups.export(user, response.getOutputStream());
        response.flushBuffer();
    }

    /** Soma os dados do arquivo aos da conta; o que já existe é pulado. */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Imported restore(@RequestParam("file") MultipartFile file) throws IOException {
        User user = currentUser.get();
        premium.require(user, "O backup não está liberado para a sua conta.");
        if (file.isEmpty()) {
            throw new BusinessException("Envie o arquivo .zip exportado pelo ContasLT");
        }
        return backups.restore(user, file.getInputStream());
    }
}
