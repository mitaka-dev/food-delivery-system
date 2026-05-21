CREATE TABLE deliveries (
    id          UUID         PRIMARY KEY,
    order_id    UUID         NOT NULL,
    username    VARCHAR(255) NOT NULL,
    status      VARCHAR(50)  NOT NULL,
    driver_name VARCHAR(255),
    created_at  TIMESTAMP    NOT NULL
);

CREATE INDEX idx_deliveries_order_id ON deliveries (order_id);
