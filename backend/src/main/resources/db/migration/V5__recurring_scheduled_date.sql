-- Cada ocorrência de conta recorrente guarda a data prevista pela regra, separada da data real de vencimento.
-- Assim o usuário pode mover uma ocorrência sem que o gerador recrie o vencimento original.
ALTER TABLE transactions ADD COLUMN scheduled_date DATE;
UPDATE transactions SET scheduled_date = due_date WHERE recurring_bill_id IS NOT NULL;
DROP INDEX uk_tx_recurring_date;
CREATE UNIQUE INDEX uk_tx_recurring_scheduled ON transactions (recurring_bill_id, scheduled_date);

-- Número da ocorrência em start_date (1 = primeira). Muda quando o calendário é reancorado a partir de uma parcela.
ALTER TABLE recurring_bills ADD COLUMN start_number INTEGER NOT NULL DEFAULT 1;
