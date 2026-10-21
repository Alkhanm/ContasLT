package br.com.contaslt.receipt;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import br.com.contaslt.common.BusinessException;
import br.com.contaslt.config.AppProperties;
import br.com.contaslt.receipt.ReceiptDtos.ReceiptScan;
import br.com.contaslt.receipt.ReceiptDtos.ScannedItem;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Lê um comprovante (foto de cupom fiscal, NFC-e, recibo) com um modelo de visão servido pelo Ollama,
 * pedindo a resposta em JSON com esquema fixo (structured outputs).
 */
@Component
public class ReceiptScanner {

    private static final Logger log = LoggerFactory.getLogger(ReceiptScanner.class);

    static final String PROMPT = """
        Esta imagem é um comprovante de compra brasileiro (cupom fiscal, NFC-e, DANFE, recibo ou comprovante de pagamento).
        Extraia em JSON:
        - merchant: nome fantasia do estabelecimento (curto, sem CNPJ nem endereço);
        - date: data de emissão/pagamento no formato AAAA-MM-DD;
        - total: valor efetivamente pago, número com ponto decimal;
        - paymentMethod: forma de pagamento, se aparecer (ex.: "Cartão de débito", "Pix", "Dinheiro");
        - accessKey: chave de acesso de 44 dígitos sem espaços, se aparecer; senão string vazia;
        - items: um objeto por linha de produto, com description (nome do produto), quantity, unitPrice e total,
          usando ponto decimal. Não inclua totais, subtotais, impostos, descontos ou troco como itens.
        Não invente dados. Se não houver itens legíveis, devolva items vazio.
        """;

    private final AppProperties props;
    private final ObjectMapper json;
    private final HttpClient http;

    public ReceiptScanner(AppProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public ReceiptScan scan(byte[] image) {
        ObjectNode body = json.createObjectNode();
        body.put("model", props.ollama().model());
        body.put("stream", false);
        body.put("think", false);
        body.set("format", schema());
        body.putObject("options").put("temperature", 0.1).put("num_predict", 4000);
        ObjectNode message = body.putArray("messages").addObject();
        message.put("role", "user");
        message.put("content", PROMPT);
        message.putArray("images").add(Base64.getEncoder().encodeToString(image));

        HttpRequest request = HttpRequest.newBuilder(URI.create(props.ollama().url() + "/api/chat"))
            .timeout(props.ollama().timeout())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
            .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            log.warn("Ollama indisponível em {}: {}", props.ollama().url(), e.toString());
            throw new BusinessException("O leitor de comprovantes (Ollama) não respondeu. Verifique se ele está rodando.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("Leitura do comprovante interrompida");
        }
        if (response.statusCode() >= 300) {
            log.warn("Ollama respondeu {}: {}", response.statusCode(), response.body());
            throw new BusinessException("O leitor de comprovantes falhou (" + response.statusCode()
                + "). Confira se o modelo " + props.ollama().model() + " está instalado.");
        }
        JsonNode content = json.readTree(response.body()).path("message").path("content");
        return parse(content.asString());
    }

    /** Converte a resposta JSON do modelo no registro tipado, tolerando campos ausentes ou mal formatados. */
    ReceiptScan parse(String content) {
        JsonNode n;
        try {
            n = json.readTree(content);
        } catch (RuntimeException e) {
            throw new BusinessException("Não foi possível entender o comprovante. Tente uma foto mais nítida.");
        }
        List<ScannedItem> items = new ArrayList<>();
        for (JsonNode i : n.path("items")) {
            String desc = text(i, "description");
            BigDecimal total = number(i, "total");
            if (desc.isEmpty() || total == null) {
                continue;
            }
            items.add(new ScannedItem(desc, number(i, "quantity"), number(i, "unitPrice"), total));
        }
        String key = text(n, "accessKey").replaceAll("\\D", "");
        return new ReceiptScan(text(n, "merchant"), date(text(n, "date")), number(n, "total"),
            text(n, "paymentMethod"), key.length() == 44 ? key : null, items);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? "" : v.asString().trim();
    }

    private static BigDecimal number(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            return v.decimalValue();
        }
        String s = v.asString().replaceAll("[^0-9,.-]", "");
        if (s.isEmpty()) {
            return null;
        }
        if (s.contains(",") && (!s.contains(".") || s.lastIndexOf(',') > s.lastIndexOf('.'))) {
            s = s.replace(".", "").replace(',', '.');
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate date(String s) {
        if (s.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(s.substring(0, Math.min(10, s.length())));
        } catch (DateTimeParseException | StringIndexOutOfBoundsException e) {
            var m = java.util.regex.Pattern.compile("(\\d{2})/(\\d{2})/(\\d{4})").matcher(s);
            if (m.find()) {
                return LocalDate.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
            }
            return null;
        }
    }

    private ObjectNode schema() {
        ObjectNode item = json.createObjectNode();
        item.put("type", "object");
        ObjectNode ip = item.putObject("properties");
        ip.putObject("description").put("type", "string");
        ip.putObject("quantity").put("type", "number");
        ip.putObject("unitPrice").put("type", "number");
        ip.putObject("total").put("type", "number");
        ArrayNode ireq = item.putArray("required");
        ireq.add("description").add("quantity").add("unitPrice").add("total");

        ObjectNode root = json.createObjectNode();
        root.put("type", "object");
        ObjectNode p = root.putObject("properties");
        p.putObject("merchant").put("type", "string");
        p.putObject("date").put("type", "string");
        p.putObject("total").put("type", "number");
        p.putObject("paymentMethod").put("type", "string");
        p.putObject("accessKey").put("type", "string");
        ObjectNode items = p.putObject("items");
        items.put("type", "array");
        items.set("items", item);
        root.putArray("required").add("merchant").add("date").add("total").add("items");
        return root;
    }
}
