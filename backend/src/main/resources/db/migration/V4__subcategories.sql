-- Subcategorias: um nível só (categoria pai > subcategoria).
ALTER TABLE categories ADD COLUMN parent_id BIGINT REFERENCES categories (id);
CREATE INDEX ix_categories_parent ON categories (parent_id);
