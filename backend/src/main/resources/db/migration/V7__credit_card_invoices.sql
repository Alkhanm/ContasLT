-- Cartão de crédito: dia de fechamento e de vencimento da fatura (só para contas do tipo CREDIT_CARD).
ALTER TABLE accounts ADD COLUMN closing_day INTEGER;
ALTER TABLE accounts ADD COLUMN due_day INTEGER;

-- Pagamento de fatura = transferência conta → cartão; guarda o mês (primeiro dia) da fatura que ela paga.
ALTER TABLE transfers ADD COLUMN invoice_month DATE;

-- "Compras não detalhadas": despesa gerada pelo app no cartão quando o pagamento da fatura supera as compras registradas.
ALTER TABLE transactions ADD COLUMN invoice_gap BOOLEAN NOT NULL DEFAULT FALSE;
