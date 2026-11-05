package br.com.contaslt.sync;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

public final class SyncDtos {

    private SyncDtos() {
    }

    /**
     * Um registro como o cliente o guarda. {@code data} é opaco para o servidor (nulo quando {@code deleted});
     * {@code updatedAt} é o relógio do cliente em epoch ms e decide conflitos.
     */
    public record SyncRecordDto(UUID id, String entity, JsonNode data, boolean deleted, long version, long updatedAt) {
    }

    public record PullResponse(List<SyncRecordDto> records, long cursor, boolean hasMore) {
    }

    public record Change(
            @NotNull UUID id,
            @NotNull @Pattern(regexp = "[a-zA-Z]{1,40}") String entity,
            JsonNode data,
            boolean deleted,
            @NotNull Long updatedAt) {
    }

    public record PushRequest(@NotNull @Size(max = SyncService.MAX_PUSH) List<@Valid Change> changes) {
    }

    /** {@code rejected}: registros que o servidor já tinha numa gravação mais nova, na versão dele (o cliente aplica). */
    public record PushResponse(int accepted, List<SyncRecordDto> rejected) {
    }
}
