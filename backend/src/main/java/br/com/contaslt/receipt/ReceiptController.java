package br.com.contaslt.receipt;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import br.com.contaslt.common.NotFoundException;
import br.com.contaslt.receipt.ReceiptDtos.ReceiptScanResponse;
import br.com.contaslt.user.CurrentUser;

@RestController
@RequestMapping("/api/receipts")
public class ReceiptController {

    private final ReceiptService receipts;
    private final CurrentUser currentUser;

    public ReceiptController(ReceiptService receipts, CurrentUser currentUser) {
        this.receipts = receipts;
        this.currentUser = currentUser;
    }

    /** Envia a foto do comprovante; devolve os dados lidos e o nome do arquivo para anexar ao lançamento. */
    @PostMapping("/scan")
    public ReceiptScanResponse scan(@RequestParam("file") MultipartFile file,
            @RequestParam(value = "ignoreTransactionId", required = false) Long ignoreTransactionId) {
        return receipts.scan(currentUser.get(), file, ignoreTransactionId);
    }

    @GetMapping(value = "/{file}", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> image(@PathVariable String file) {
        byte[] bytes = receipts.image(currentUser.id(), file)
            .orElseThrow(() -> new NotFoundException("Comprovante não encontrado"));
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(30)).cachePrivate())
            .contentType(MediaType.IMAGE_JPEG).body(bytes);
    }
}
