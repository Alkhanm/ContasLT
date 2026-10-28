package br.com.contaslt.category;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class SubcategoryTest {

    private static final String MONTH = "2031-03";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ObjectMapper json;

    private String token;
    private long accountId;
    private long casa;
    private long energia;
    private long agua;

    @BeforeEach
    void setUp() {
        String email = "sub" + System.nanoTime() + "@exemplo.com";
        token = body(mvc.post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("name", "Sub", "email", email, "password", "segredo1")))
            .exchange()).get("token").asString();
        accountId = body(mvc.get().uri("/api/accounts").headers(auth()).exchange()).get(0).get("id").asLong();
        casa = category("Contas da casa", "EXPENSE", null);
        energia = category("Energia", "EXPENSE", casa);
        agua = category("Água", "INCOME", casa); // tipo informado errado de propósito: segue o pai
    }

    @Test
    void subcategoryFollowsParentTypeAndHasOneLevelOnly() {
        JsonNode list = body(mvc.get().uri("/api/categories").headers(auth()).exchange());
        JsonNode aguaDto = find(list, agua);
        assertThat(aguaDto.get("type").asString()).isEqualTo("EXPENSE");
        assertThat(aguaDto.get("parentId").asLong()).isEqualTo(casa);
        assertThat(find(list, casa).get("parentId").isNull()).isTrue();

        MvcTestResult nested = mvc.post().uri("/api/categories").headers(auth()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(payload("Neta", "EXPENSE", energia))).exchange();
        assertThat(nested).hasStatus(422);

        MvcTestResult self = mvc.put().uri("/api/categories/" + casa).headers(auth()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(payload("Contas da casa", "EXPENSE", casa))).exchange();
        assertThat(self).hasStatus(422);

        MvcTestResult parentToChild = mvc.put().uri("/api/categories/" + casa).headers(auth())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(payload("Contas da casa", "EXPENSE", energia))).exchange();
        assertThat(parentToChild).hasStatus(422);
    }

    @Test
    void filteringByParentIncludesChildrenAndDashboardGroupsByParent() {
        transaction("Enel", 200, energia);
        transaction("Saae", 80, agua);
        transaction("Condomínio", 500, casa);
        transaction("Outro", 10, null);

        JsonNode byParent = body(mvc.get().uri("/api/transactions?month=" + MONTH + "&categoryId=" + casa)
            .headers(auth()).exchange()).get("items");
        assertThat(byParent).hasSize(3);

        JsonNode byChild = body(mvc.get().uri("/api/transactions?month=" + MONTH + "&categoryId=" + energia)
            .headers(auth()).exchange()).get("items");
        assertThat(byChild).hasSize(1);
        assertThat(byChild.get(0).get("description").asString()).isEqualTo("Enel");

        JsonNode dash = body(mvc.get().uri("/api/dashboard?month=" + MONTH).headers(auth()).exchange()).get("byCategory");
        assertThat(dash).hasSize(2); // Contas da casa (780) e Sem categoria (10)
        assertThat(dash.get(0).get("categoryId").asLong()).isEqualTo(casa);
        assertThat(dash.get(0).get("total").decimalValue()).isEqualByComparingTo("780");
        assertThat(dash.get(0).get("count").asInt()).isEqualTo(3);
    }

    @Test
    void parentWithChildrenCannotBeDeletedAndArchivingCascades() {
        assertThat(mvc.delete().uri("/api/categories/" + casa).headers(auth()).exchange()).hasStatus(422);

        Map<String, Object> archive = payload("Contas da casa", "EXPENSE", null);
        archive.put("archived", true);
        assertThat(mvc.put().uri("/api/categories/" + casa).headers(auth()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(archive)).exchange()).hasStatusOk();
        JsonNode list = body(mvc.get().uri("/api/categories?includeArchived=true").headers(auth()).exchange());
        assertThat(find(list, energia).get("archived").asBoolean()).isTrue();
        assertThat(find(list, agua).get("archived").asBoolean()).isTrue();

        assertThat(mvc.delete().uri("/api/categories/" + energia).headers(auth()).exchange()).hasStatus(204);
    }

    private long category(String name, String type, Long parentId) {
        MvcTestResult res = mvc.post().uri("/api/categories").headers(auth()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(payload(name, type, parentId))).exchange();
        assertThat(res).hasStatus(201);
        return body(res).get("id").asLong();
    }

    private static Map<String, Object> payload(String name, String type, Long parentId) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("type", type);
        m.put("color", "#3D8361");
        m.put("icon", "tag");
        if (parentId != null) {
            m.put("parentId", parentId);
        }
        return m;
    }

    private void transaction(String description, int amount, Long categoryId) {
        Map<String, Object> m = new HashMap<>();
        m.put("description", description);
        m.put("amount", amount);
        m.put("type", "EXPENSE");
        m.put("dueDate", MONTH + "-10");
        m.put("paid", true);
        m.put("accountId", accountId);
        if (categoryId != null) {
            m.put("categoryId", categoryId);
        }
        assertThat(mvc.post().uri("/api/transactions").headers(auth()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(m)).exchange()).hasStatus(201);
    }

    private static JsonNode find(JsonNode list, long id) {
        for (JsonNode n : list) {
            if (n.get("id").asLong() == id) {
                return n;
            }
        }
        throw new AssertionError("categoria " + id + " não encontrada");
    }

    private JsonNode body(MvcTestResult result) {
        try {
            return json.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private HttpHeaders auth() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }
}
