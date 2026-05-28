ALTER TABLE products ADD COLUMN restaurant_id UUID;

CREATE INDEX idx_products_restaurant_id ON products(restaurant_id);
