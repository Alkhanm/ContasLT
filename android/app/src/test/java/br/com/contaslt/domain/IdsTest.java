package br.com.contaslt.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

/** Porte de frontend/src/domain/ids.test.ts, mais os ids conferidos contra o PWA. */
public class IdsTest {

    @Test
    public void uuidV5IgualAoDaRfc() {
        assertEquals("2ed6657d-e927-568b-95e1-2665a8aea6a2",
            Ids.uuidV5("www.example.com", "6ba7b810-9dad-11d1-80b4-00c04fd430c8"));
    }

    @Test
    public void sha1DeAbc() {
        StringBuilder hex = new StringBuilder();
        for (byte b : Ids.sha1("abc".getBytes(StandardCharsets.UTF_8))) {
            hex.append(String.format("%02x", b));
        }
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", hex.toString());
    }

    @Test
    public void vencimentoMesmoIdParaMesmaRegraEData() {
        assertEquals(Ids.occurrenceId("b1", "2026-01-05"), Ids.occurrenceId("b1", "2026-01-05"));
        assertNotEquals(Ids.occurrenceId("b1", "2026-01-05"), Ids.occurrenceId("b1", "2026-02-05"));
    }

    @Test
    public void mesmosIdsDoPwa() {
        assertEquals("42da7829-e173-551f-9ace-4c648b4553b7", Ids.occurrenceId("b1", "2026-01-05"));
        assertEquals("f31858d7-6dd6-59ef-b584-829c69ce65e5",
            Ids.occurrenceId("3f2a0c1e-0000-4000-8000-000000000001", "2026-11-10"));
        assertEquals("5a195321-a9a6-51f2-a603-b4fc493f9a0c", Ids.occurrenceId("b1", "2026-01-05#2"));
    }

    @Test
    public void novoIdEhV4() {
        String id = Ids.newId();
        assertEquals('4', id.charAt(14));
    }
}
