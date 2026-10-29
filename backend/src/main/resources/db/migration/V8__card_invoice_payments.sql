-- Fatura de cartão passa a ser uma despesa comum na conta que pagou, ligada ao cartão e ao mês da fatura;
-- as compras do cartão ficam agregadas dentro dela (pelo período entre fechamentos) e saem das listas e totais.
DELETE FROM transactions WHERE invoice_gap;
ALTER TABLE transactions DROP COLUMN invoice_gap;
ALTER TABLE transfers DROP COLUMN invoice_month;

ALTER TABLE transactions ADD COLUMN card_account_id BIGINT REFERENCES accounts (id) ON DELETE SET NULL;
ALTER TABLE transactions ADD COLUMN card_invoice_month DATE;
CREATE INDEX ix_tx_card_invoice ON transactions (card_account_id, card_invoice_month);
