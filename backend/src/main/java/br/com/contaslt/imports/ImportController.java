package br.com.contaslt.imports;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import br.com.contaslt.imports.ImportDtos.ImportBatchDto;
import br.com.contaslt.imports.ImportDtos.ImportConfirmRequest;
import br.com.contaslt.imports.ImportDtos.ImportPreview;
import br.com.contaslt.imports.ImportDtos.ImportResult;
import br.com.contaslt.user.CurrentUser;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/imports")
public class ImportController {

    private final ImportService imports;
    private final CurrentUser currentUser;

    public ImportController(ImportService imports, CurrentUser currentUser) {
        this.imports = imports;
        this.currentUser = currentUser;
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportPreview preview(@RequestParam Long accountId, @RequestPart("file") MultipartFile file) {
        return imports.preview(currentUser.get(), accountId, file);
    }

    @PostMapping("/confirm")
    @ResponseStatus(HttpStatus.CREATED)
    public ImportResult confirm(@Valid @RequestBody ImportConfirmRequest req) {
        return imports.confirm(currentUser.get(), req);
    }

    @GetMapping
    public List<ImportBatchDto> history() {
        return imports.history(currentUser.id());
    }

    /** Exclui a importação e todos os lançamentos gerados por ela. */
    @DeleteMapping("/{id}")
    public Map<String, Integer> delete(@PathVariable Long id) {
        return Map.of("removed", imports.delete(currentUser.id(), id));
    }
}
