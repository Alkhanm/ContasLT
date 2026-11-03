package br.com.contaslt;

import java.util.Arrays;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import br.com.contaslt.worker.ReceiptWorker;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ContasltApplication {

    public static void main(String[] args) throws Exception {
        // O mesmo jar roda no computador do usuário como worker da fila de comprovantes (sem Spring, sem banco).
        if (Arrays.asList(args).contains("--worker")) {
            ReceiptWorker.run();
            return;
        }
        SpringApplication.run(ContasltApplication.class, args);
    }
}
