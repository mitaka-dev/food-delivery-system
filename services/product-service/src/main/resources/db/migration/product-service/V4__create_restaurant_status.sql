CREATE TABLE restaurant_status (
    restaurant_id UUID        PRIMARY KEY,
    paused        BOOLEAN     NOT NULL DEFAULT false,
    paused_at     TIMESTAMPTZ,
    resumed_at    TIMESTAMPTZ
);
