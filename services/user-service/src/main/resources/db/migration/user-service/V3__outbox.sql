-- Spring Modulith JDBC event publication table (shared schema, sourced from Step 1.4)
CREATE TABLE event_publication (
    id               UUID          NOT NULL,
    listener_id      VARCHAR(512)  NOT NULL,
    event_type       VARCHAR(512)  NOT NULL,
    serialized_event TEXT          NOT NULL,
    publication_date TIMESTAMPTZ(6) NOT NULL,
    completion_date  TIMESTAMPTZ(6),
    PRIMARY KEY (id)
);

-- Partial index speeds up the outbox publisher's "find incomplete" query
CREATE INDEX idx_event_pub_incomplete
    ON event_publication (publication_date)
    WHERE completion_date IS NULL;
