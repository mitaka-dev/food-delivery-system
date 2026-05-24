ALTER TABLE users ADD COLUMN username VARCHAR(100) NOT NULL DEFAULT '';
ALTER TABLE users ADD CONSTRAINT uq_users_username UNIQUE (username);
CREATE INDEX idx_users_username ON users (username);
