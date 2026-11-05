-- Local-first: os clientes (PWA e app Android) guardam todos os dados e executam as regras de negócio; o servidor
-- só guarda e sincroniza. Cada registro é um documento JSON opaco para o servidor, identificado por um UUID gerado
-- no cliente. "version" vem de uma sequência global e cresce a cada gravação: o cliente pede tudo acima da maior
-- versão que já viu. "updated_at" é o relógio do cliente (epoch ms) e decide conflitos (vence a gravação mais nova).
-- Exclusão é marcação (deleted = TRUE, data nulo), para chegar aos outros aparelhos.
CREATE SEQUENCE sync_version_seq;

CREATE TABLE sync_records (
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    id         UUID        NOT NULL,
    entity     VARCHAR(40) NOT NULL,
    data       TEXT,
    deleted    BOOLEAN     NOT NULL DEFAULT FALSE,
    version    BIGINT      NOT NULL,
    updated_at BIGINT      NOT NULL,
    PRIMARY KEY (user_id, id)
);
CREATE INDEX ix_sync_user_version ON sync_records (user_id, version);

-- A leitura adiada do comprovante passa a apontar para o lançamento do cliente (UUID), não para a tabela antiga.
ALTER TABLE receipt_jobs ADD COLUMN transaction_uuid UUID;
