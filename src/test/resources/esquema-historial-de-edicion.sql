-- Tabla mínima del historial de cambios de una orden, tal como la deja
-- ms-order-product (la escribe él; el core solo la lee). Incluye `discount_code`,
-- que añadió su V57 al registrar el rastro de un descuento.
CREATE TABLE order_edit_history (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     TEXT,
    order_id      BIGINT NOT NULL,
    edit_type     TEXT,
    product_id    TEXT,
    product_name  TEXT,
    old_quantity  INTEGER,
    new_quantity  INTEGER,
    old_total     NUMERIC(38,2),
    new_total     NUMERIC(38,2),
    discount_code TEXT,
    edited_by     TEXT,
    edited_at     TIMESTAMP
);
