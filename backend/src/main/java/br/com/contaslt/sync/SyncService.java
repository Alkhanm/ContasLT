package br.com.contaslt.sync;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.contaslt.common.BusinessException;
import br.com.contaslt.sync.SyncDtos.Change;
import br.com.contaslt.sync.SyncDtos.PullResponse;
import br.com.contaslt.sync.SyncDtos.PushResponse;
import br.com.contaslt.sync.SyncDtos.SyncRecordDto;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Armazena os documentos dos clientes sem interpretar o conteúdo. Conflito: vence o {@code updatedAt} maior
 * (empate aceita a gravação nova, que é idempotente para o mesmo cliente). Cada gravação recebe uma versão nova
 * da sequência; o pull devolve tudo acima do cursor do cliente, em ordem.
 *
 * <p>Os pushes de um mesmo usuário são serializados por um lock na linha dele em {@code users}: sem isso, duas
 * gravações concorrentes poderiam ficar visíveis fora da ordem das versões e um pull no meio pularia a menor.
 */
@Service
public class SyncService {

    static final int MAX_PUSH = 500;
    static final int MAX_PULL = 2000;
    /** Um lançamento com centenas de itens de cupom fica bem abaixo disso. */
    static final int MAX_RECORD_CHARS = 256 * 1024;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public SyncService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public PullResponse pull(long userId, long since, int limit) {
        int size = Math.max(1, Math.min(limit, MAX_PULL));
        List<SyncRecordDto> records = jdbc.query(
            "SELECT id, entity, data, deleted, version, updated_at FROM sync_records "
                + "WHERE user_id = ? AND version > ? ORDER BY version LIMIT ?",
            (rs, i) -> new SyncRecordDto(rs.getObject("id", UUID.class), rs.getString("entity"),
                readData(rs.getString("data")), rs.getBoolean("deleted"), rs.getLong("version"), rs.getLong("updated_at")),
            userId, since, size);
        long cursor = records.isEmpty() ? since : records.get(records.size() - 1).version();
        return new PullResponse(records, cursor, records.size() == size);
    }

    @Transactional
    public PushResponse push(long userId, List<Change> changes) {
        if (changes.isEmpty()) {
            return new PushResponse(0, List.of());
        }
        jdbc.queryForList("SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, userId);

        // Se o mesmo id vier mais de uma vez no lote, vale a última ocorrência com o maior updatedAt.
        Map<UUID, Change> byId = new LinkedHashMap<>();
        for (Change c : changes) {
            Change prev = byId.get(c.id());
            if (prev == null || c.updatedAt() >= prev.updatedAt()) {
                byId.put(c.id(), c);
            }
        }
        Map<UUID, long[]> existing = existing(userId, new ArrayList<>(byId.keySet()));

        List<Object[]> inserts = new ArrayList<>();
        List<Object[]> updates = new ArrayList<>();
        List<UUID> rejected = new ArrayList<>();
        for (Change c : byId.values()) {
            String data = c.deleted() ? null : writeData(c);
            long[] cur = existing.get(c.id());
            if (cur == null) {
                inserts.add(new Object[] {userId, c.id(), c.entity(), data, c.deleted(), c.updatedAt()});
            } else if (c.updatedAt() >= cur[0]) {
                updates.add(new Object[] {c.entity(), data, c.deleted(), c.updatedAt(), userId, c.id()});
            } else {
                rejected.add(c.id());
            }
        }
        if (!inserts.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO sync_records (user_id, id, entity, data, deleted, version, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, nextval('sync_version_seq'), ?)", inserts);
        }
        if (!updates.isEmpty()) {
            jdbc.batchUpdate("UPDATE sync_records SET entity = ?, data = ?, deleted = ?, "
                + "version = nextval('sync_version_seq'), updated_at = ? WHERE user_id = ? AND id = ?", updates);
        }
        return new PushResponse(inserts.size() + updates.size(), rejected);
    }

    /** updated_at atual de cada id que já existe, em consultas de até 200 ids. */
    private Map<UUID, long[]> existing(long userId, List<UUID> ids) {
        Map<UUID, long[]> out = new HashMap<>();
        for (int from = 0; from < ids.size(); from += 200) {
            List<UUID> chunk = ids.subList(from, Math.min(ids.size(), from + 200));
            String marks = String.join(",", Collections.nCopies(chunk.size(), "?"));
            List<Object> args = new ArrayList<>();
            args.add(userId);
            args.addAll(chunk);
            jdbc.query("SELECT id, updated_at FROM sync_records WHERE user_id = ? AND id IN (" + marks + ")",
                rs -> {
                    out.put(rs.getObject("id", UUID.class), new long[] {rs.getLong("updated_at")});
                }, args.toArray());
        }
        return out;
    }

    private String writeData(Change c) {
        if (c.data() == null || !c.data().isObject()) {
            throw new BusinessException("Registro " + c.id() + " sem dados");
        }
        String s = json.writeValueAsString(c.data());
        if (s.length() > MAX_RECORD_CHARS) {
            throw new BusinessException("Registro " + c.id() + " grande demais");
        }
        return s;
    }

    private JsonNode readData(String s) {
        return s == null ? null : json.readTree(s);
    }
}
