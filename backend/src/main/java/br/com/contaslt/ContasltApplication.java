package br.com.contaslt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ContasltApplication {

    public static void main(String[] args) {
        SpringApplication.run(ContasltApplication.class, args);
    }
}
