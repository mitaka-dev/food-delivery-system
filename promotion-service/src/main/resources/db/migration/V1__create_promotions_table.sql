CREATE TABLE promotions (
    id               UUID         PRIMARY KEY,
    code             VARCHAR(50)  NOT NULL UNIQUE,
    discount_percent INT          NOT NULL,
    active           BOOLEAN      NOT NULL DEFAULT true,
    created_at       TIMESTAMP    NOT NULL
);

CREATE INDEX idx_promotions_code ON promotions (code);
