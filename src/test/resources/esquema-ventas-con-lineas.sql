-- La venta y sus líneas TAL COMO ESTÁN EN LA NUBE, no como las imagina este
-- servicio.
--
-- Copiado de `ms-order-product-mt`, que es quien manda en este esquema:
--   * `V1__multitenant_baseline.sql:10-41` — `orders` y `order_item`, las dos
--     con `uuid_id` de CLAVE PRIMARIA.
--   * `V1:32` — `id_order_item BIGINT`, columna suelta: **sin PRIMARY KEY, sin
--     secuencia, sin DEFAULT y sin trigger que la rellene**. Está en las
--     migraciones una sola vez, la de su creación (`grep -rn id_order_item`
--     sobre `db/migration` devuelve esa única línea).
--   * `V5__id_order_per_tenant.sql` — `id_order` es correlativo POR NEGOCIO, lo
--     pone un trigger, y solo es único junto al `tenant_id`.
--   * `V10`, `V15` — `waiter_id`, `deleted_at`.
--
-- Lo que importa para la prueba, y por eso la columna se deja EXACTAMENTE
-- igual de desnuda que en producción: nadie escribe `id_order_item`.
-- `OrderHandler.java:1183-1198` (el camino JPA, el del POS) crea el `OrderItem`
-- y asigna `order`, `orderId`, `productId`, `quantity`… pero nunca
-- `idOrderItem`; y el camino de la nube
-- (`PostgresOrderCloudSyncAdapter.java:196-210`) tampoco la nombra en su
-- INSERT. En la nube esa columna es NULL en todas las filas.

CREATE TABLE orders (
    uuid_id             UUID PRIMARY KEY,
    tenant_id           TEXT NOT NULL,
    id_order            BIGINT,
    pager_color         TEXT,
    pager_number        TEXT,
    created_at          TIMESTAMP,
    delivered_at        TIMESTAMP,
    status              TEXT,
    payment_method      TEXT,
    subtotal            NUMERIC(15,2),
    total               NUMERIC(15,2),
    discount_code       TEXT,
    discount_percentage NUMERIC(15,2),
    discount_amount     NUMERIC(15,2),
    synced              BOOLEAN,
    is_printed          BOOLEAN,
    waiter_id           BIGINT,
    deleted_at          TIMESTAMP
);

-- V5: el número de orden es único DENTRO del negocio, no fuera.
ALTER TABLE orders ADD CONSTRAINT uq_orders_tenant_id_order UNIQUE (tenant_id, id_order);

CREATE TABLE order_item (
    uuid_id        UUID PRIMARY KEY,
    tenant_id      TEXT NOT NULL,
    id_order_item  BIGINT,
    order_id       BIGINT,
    order_uuid_id  UUID,
    product_id     TEXT,
    quantity       INT,
    unit_price     NUMERIC(15,2),
    total_price    NUMERIC(15,2),
    instructions   TEXT,
    combo_group    INT
);

CREATE INDEX idx_order_item_order_uuid ON order_item (order_uuid_id);

-- `Order.deliveryTracking` es @OneToOne EAGER: sin esta tabla no se puede leer
-- ni una orden.
--
-- Copiada de `V2__multitenant_remaining_tables.sql:11-18` SIN retocarla: la
-- clave primaria es `order_id_uuid` y `order_id` es una columna suelta y
-- NULLABLE. Se deja así a propósito aunque esta prueba no inserte seguimiento:
-- es la misma forma que tiene `order_item`, y este servicio la mapea con el
-- mismo criterio discutible (`OrderDeliveryTracking.java:13-15` pone el @Id en
-- `order_id`). Si alguien escribe aquí una PK que la nube no tiene, la próxima
-- prueba que use este esquema medirá algo que en producción no ocurre.
CREATE TABLE order_delivery_tracking (
    order_id_uuid                UUID PRIMARY KEY,
    tenant_id                    TEXT NOT NULL,
    order_id                     BIGINT,
    delivered                    BOOLEAN NOT NULL DEFAULT FALSE,
    pager_returned               BOOLEAN NOT NULL DEFAULT FALSE,
    preparation_duration_seconds INTEGER
);
