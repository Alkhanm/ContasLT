-- Precisão exigida para casar linhas de extrato ou comprovantes com os vencimentos pendentes da regra (1 = só o nome e o mês, 10 = nome, valor e data exatos).
ALTER TABLE recurring_bills ADD COLUMN match_strictness INTEGER NOT NULL DEFAULT 5;
