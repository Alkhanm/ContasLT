package br.com.contaslt;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
class SyncTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ObjectMapper json;

    private JsonNode body(MvcTestResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private HttpHeaders register(String email) throws Exception {
        MvcTestResult res = mvc.post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("name", "X", "email", email, "password", "segredo1"))).exchange();
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(body(res).get("token").asString());
        return h;
    }

    private JsonNode push(HttpHeaders auth, List<Map<String, Object>> changes) throws Exception {
        MvcTestResult r = mvc.post().uri("/api/sync/push").headers(auth).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("changes", changes))).exchange();
        assertThat(r).hasStatusOk();
        return body(r);
    }

    private JsonNode pull(HttpHeaders auth, long since) throws Exception {
        MvcTestResult r = mvc.get().uri("/api/sync/pull?since=" + since).headers(auth).exchange();
        assertThat(r).hasStatusOk();
        return body(r);
    }

    private static Map<String, Object> change(UUID id, String name, long updatedAt) {
        return Map.of("id", id.toString(), "entity", "account", "data", Map.of("id", id.toString(), "name", name),
            "deleted", false, "updatedAt", updatedAt);
    }

    @Test
    void pushThenPullWithCursorLastWriteWinsAndTombstones() throws Exception {
        HttpHeaders ana = register("sync-ana@exemplo.com");
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        long base = pull(ana, 0).get("cursor").asLong();

        JsonNode res = push(ana, List.of(change(a, "Nubank", 100), change(b, "Itaú", 100)));
        assertThat(res.get("accepted").asInt()).isEqualTo(2);

        JsonNode first = pull(ana, base);
        assertThat(first.get("records")).hasSize(2);
        long cursor = first.get("cursor").asLong();
        assertThat(pull(ana, cursor).get("records")).isEmpty();

        // Gravação mais velha que a guardada é recusada; mais nova vence.
        JsonNode stale = push(ana, List.of(change(a, "velho", 50)));
        assertThat(stale.get("rejected")).hasSize(1);
        push(ana, List.of(change(a, "Nubank PJ", 200)));
        JsonNode after = pull(ana, cursor);
        assertThat(after.get("records")).hasSize(1);
        assertThat(after.get("records").get(0).get("data").get("name").asString()).isEqualTo("Nubank PJ");

        // Exclusão chega como marcação, sem dados.
        long cursor2 = after.get("cursor").asLong();
        push(ana, List.of(Map.of("id", b.toString(), "entity", "account", "deleted", true, "updatedAt", 300)));
        JsonNode del = pull(ana, cursor2).get("records").get(0);
        assertThat(del.get("deleted").asBoolean()).isTrue();
        assertThat(del.get("data").isNull()).isTrue();

        // Outro usuário não vê nada disso, nem com o mesmo id.
        HttpHeaders bia = register("sync-bia@exemplo.com");
        long biaBase = pull(bia, 0).get("cursor").asLong();
        push(bia, List.of(change(a, "da Bia", 1)));
        JsonNode biaRecords = pull(bia, biaBase).get("records");
        assertThat(biaRecords).hasSize(1);
        assertThat(biaRecords.get(0).get("data").get("name").asString()).isEqualTo("da Bia");
        JsonNode anaA = null;
        for (JsonNode r : pull(ana, 0).get("records")) {
            if (r.get("id").asString().equals(a.toString())) {
                anaA = r;
            }
        }
        assertThat(anaA.get("data").get("name").asString()).isEqualTo("Nubank PJ");
    }

    @Test
    void pullPagesInVersionOrder() throws Exception {
        HttpHeaders h = register("sync-pages@exemplo.com");
        long base = pull(h, 0).get("cursor").asLong();
        List<Map<String, Object>> changes = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            changes.add(change(UUID.randomUUID(), "c" + i, 1));
        }
        push(h, changes);
        JsonNode page = body(mvc.get().uri("/api/sync/pull?since=" + base + "&limit=3").headers(h).exchange());
        assertThat(page.get("records")).hasSize(3);
        assertThat(page.get("hasMore").asBoolean()).isTrue();
        JsonNode rest = pull(h, page.get("cursor").asLong());
        assertThat(rest.get("records")).hasSize(2);
    }

    @Test
    void requiresLogin() {
        assertThat(mvc.get().uri("/api/sync/pull").exchange()).hasStatus(401);
    }
}
