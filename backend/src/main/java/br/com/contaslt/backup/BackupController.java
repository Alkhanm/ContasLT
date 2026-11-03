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

import br.com.contaslt.backup.BackupService.Restored;
import br.com.contaslt.common.BusinessException;
import br.com.contaslt.user.CurrentUser;
import br.com.contaslt.user.PremiumGuard;
import br.com.contaslt.user.User;
import jakarta.servlet.http.HttpServletResponse;

/** Backup completo (zip) do usuário: baixar e restaurar. Só para contas premium. */
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
        response.setHeader("Content-Disposition", "attachment; filename=\"contaslt-backup-" + LocalDate.now() + ".zip\"");
        backups.export(user, response.getOutputStream());
        response.flushBuffer();
    }

    /** Substitui TODOS os dados do usuário pelos do arquivo. */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Restored restore(@RequestParam("file") MultipartFile file) throws IOException {
        User user = currentUser.get();
        premium.require(user, "O backup não está liberado para a sua conta.");
        if (file.isEmpty()) {
            throw new BusinessException("Envie o arquivo .zip do backup");
        }
        return backups.restore(user, file.getInputStream());
    }
}
