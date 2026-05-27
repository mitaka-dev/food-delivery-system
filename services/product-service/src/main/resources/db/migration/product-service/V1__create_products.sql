CREATE TABLE products (
    id          UUID           PRIMARY KEY,
    name        VARCHAR(255)   NOT NULL,
    description TEXT,
    price       NUMERIC(10, 2) NOT NULL CHECK (price >= 0),
    category    VARCHAR(100)   NOT NULL,
    stock       INTEGER        NOT NULL DEFAULT 0 CHECK (stock >= 0),
    version     BIGINT         NOT NULL DEFAULT 0
);

CREATE INDEX idx_products_category ON products(category);
