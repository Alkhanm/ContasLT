package br.com.contaslt.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Ids dos registros (porte de frontend/src/domain/ids.ts): UUID v4 aleatório para o que o usuário cria e UUID v5
 * (SHA-1, RFC 4122) para o que precisa dar o mesmo id em qualquer aparelho, como o vencimento de uma recorrente numa
 * data. Mesmo namespace e mesmo nome do PWA, então os dois geram ids iguais.
 */
public final class Ids {

    /** Namespace fixo do ContasLT (não mude: ids de vencimentos já gravados dependem dele). */
    public static final String CONTASLT_NAMESPACE = "6f1c2a4e-9b7d-4c3e-8a51-2d0b7e6f9c13";

    private Ids() {
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    public static String uuidV5(String name) {
        return uuidV5(name, CONTASLT_NAMESPACE);
    }

    public static String uuidV5(String name, String namespace) {
        byte[] ns = parse(namespace);
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[ns.length + nameBytes.length];
        System.arraycopy(ns, 0, data, 0, ns.length);
        System.arraycopy(nameBytes, 0, data, ns.length, nameBytes.length);
        byte[] hash = sha1(data);
        byte[] b = new byte[16];
        System.arraycopy(hash, 0, b, 0, 16);
        b[6] = (byte) ((b[6] & 0x0f) | 0x50);
        b[8] = (byte) ((b[8] & 0x3f) | 0x80);
        return format(b);
    }

    /** Id do vencimento de uma conta recorrente na data prevista pela regra. */
    public static String occurrenceId(String billId, String scheduledDate) {
        return uuidV5("occurrence:" + billId + ":" + scheduledDate);
    }

    public static byte[] sha1(byte[] msg) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(msg);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] parse(String uuid) {
        String hex = uuid.replace("-", "");
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String format(byte[] b) {
        StringBuilder h = new StringBuilder(32);
        for (byte x : b) {
            h.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
        }
        String s = h.toString();
        return s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16) + "-" + s.substring(16, 20) + "-"
            + s.substring(20);
    }
}
