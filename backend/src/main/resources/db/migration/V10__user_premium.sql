-- Só usuários "premium" podem mandar comprovantes para leitura (a fila vai para a GPU do dono do app).
-- Não há tela para isso: liga-se direto no banco (UPDATE users SET premium = TRUE WHERE email = '...').
ALTER TABLE users ADD COLUMN premium BOOLEAN NOT NULL DEFAULT FALSE;
