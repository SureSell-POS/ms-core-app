package com.suresell.mscoreapp.infrastructure.persistence;

import com.suresell.mscoreapp.application.dto.OrderItemDto;
import com.suresell.mscoreapp.application.dto.OrderResponse;
import com.suresell.mscoreapp.application.usecase.ManageOrderUseCase;
import com.suresell.mscoreapp.application.usecase.OrderMapperImpl;
import com.suresell.mscoreapp.domain.model.MenuProductEntity;
import com.suresell.mscoreapp.domain.port.out.MenuProductRepository;
import com.suresell.mscoreapp.infrastructure.multitenant.TenantContext;
import com.suresell.mscoreapp.infrastructure.persistence.jpa.OrderJpaRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 «Ver Detalle» TIENE QUE TRAER LAS LÍNEAS DE LA VENTA.
 *
 * <h3>La contradicción que resuelve</h3>
 *
 * Medido con el ratón en staging el 2026-09-13: el panel abre el detalle de las
 * ventas #14 a #18 de {@code ponyferrelectrico} con el total correcto y sin una
 * sola línea («Esta venta no trae sus artículos»). Medido en la base el mismo
 * día: las 134 filas de {@code order_item} tienen {@code order_id} poblado
 * —{@code count(*) FILTER (WHERE order_id IS NULL) = 0}— y el listado declara
 * {@code items} en su respuesta.
 *
 * <p>Las dos cosas eran ciertas. La que faltaba mirar es OTRA columna:
 * {@code id_order_item}, que este servicio declara como su CLAVE PRIMARIA
 * ({@code OrderItem.java:18-21}) y que en la nube no la escribe nadie —
 * {@code V1__multitenant_baseline.sql:32} la crea como un {@code BIGINT} suelto,
 * sin secuencia ni default, y la clave primaria de verdad es {@code uuid_id}
 * ({@code V1:30}). Con la clave primaria a NULL, Hibernate no puede construir la
 * línea y la colección llega vacía: ni error, ni log, ni fila de menos.
 *
 * <h3>Cómo mide, para que no pueda salir verde en falso</h3>
 *
 * <ol>
 *   <li>Primero comprueba <b>por SQL</b> que la base tiene las líneas. Sin eso,
 *       una colección vacía sería «correcta» y el test pasaría con la tabla
 *       vacía, que es la forma clásica de que un verde no signifique nada.</li>
 *   <li>Después ejecuta el <b>caso de uso real</b> que sirve
 *       {@code GET /api/orders} —{@link ManageOrderUseCase}, con el mapper
 *       generado de verdad— y exige que las líneas lleguen hasta el DTO, que es
 *       lo que lee el panel.</li>
 *   <li>Y contrasta dos ventas idénticas salvo en {@code id_order_item}, para
 *       que la causa quede señalada por la medición y no por el comentario.</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// Sin la transaccion envolvente de @DataJpaTest: Hibernate resuelve el negocio AL ABRIR la
// sesion, y esa transaccion se abre antes del @BeforeEach que fija el contexto.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class ElDetalleDeLaVentaTraeSusLineasTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("esquema-ventas-con-lineas.sql");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    /** El negocio de la pantalla que se midió. */
    private static final String NEGOCIO = "ponyferrelectrico";

    @Autowired
    OrderJpaRepository ordenes;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager gestorDeTransacciones;

    @BeforeEach
    void fijarNegocioYVaciar() {
        TenantContext.set(NEGOCIO);
        jdbc.update("DELETE FROM order_item");
        jdbc.update("DELETE FROM order_delivery_tracking");
        jdbc.update("DELETE FROM orders");
    }

    @AfterEach
    void limpiarNegocio() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("🔴 el detalle de una venta de la nube trae sus líneas, aunque `id_order_item` venga vacío (nadie lo escribe)")
    void elDetalleTraeSusLineas() {
        UUID venta = UUID.randomUUID();
        crearVenta(14, venta, new BigDecimal("21000"));
        // Las dos líneas TAL COMO LAS ESCRIBE LA NUBE: con su UUID, con
        // `order_id`, y con `id_order_item` en NULL.
        crearLinea(venta, 14, null, "P-martillo", 2, new BigDecimal("8000"));
        crearLinea(venta, 14, null, "P-tornillo", 1, new BigDecimal("5000"));

        // 1) La base SÍ tiene las líneas, y con `order_id` poblado. Si esto
        //    fallara, el resto del test no mediría nada.
        assertThat(contarLineasEnLaBase(14))
                .as("la venta tiene dos líneas en la base, con order_id")
                .isEqualTo(2);

        // 2) Y el listado que sirve el panel TIENE que traerlas.
        OrderResponse respuesta = laVentaComoLaVeElPanel(14);

        assertThat(respuesta.getTotal()).isEqualByComparingTo("21000");
        assertThat(respuesta.getItems())
                .as("«Ver Detalle» las muestra desde aquí: si llega vacío, el panel dice "
                        + "que la venta no trae sus artículos")
                .hasSize(2);
        assertThat(respuesta.getItems()).extracting(OrderItemDto::getProductId)
                .containsExactlyInAnyOrder("P-martillo", "P-tornillo");
        assertThat(respuesta.getItems()).extracting(OrderItemDto::getQuantity)
                .containsExactlyInAnyOrder(2, 1);
    }

    @Test
    @DisplayName("una venta con `id_order_item` escrito también trae sus líneas: la columna no puede ser la que decide")
    void laColumnaSueltaNoDecideSiLaVentaTieneDetalle() {
        UUID conNumero = UUID.randomUUID();
        crearVenta(15, conNumero, new BigDecimal("4000"));
        crearLinea(conNumero, 15, 9001L, "P-puntilla", 4, new BigDecimal("4000"));

        UUID sinNumero = UUID.randomUUID();
        crearVenta(16, sinNumero, new BigDecimal("4000"));
        crearLinea(sinNumero, 16, null, "P-puntilla", 4, new BigDecimal("4000"));

        assertThat(contarLineasEnLaBase(15)).isEqualTo(1);
        assertThat(contarLineasEnLaBase(16)).isEqualTo(1);

        // Las dos ventas son la misma venta salvo en esa columna. Las dos
        // tienen que verse igual.
        assertThat(laVentaComoLaVeElPanel(15).getItems())
                .as("venta con id_order_item escrito").hasSize(1);
        assertThat(laVentaComoLaVeElPanel(16).getItems())
                .as("la MISMA venta con id_order_item vacío").hasSize(1);
    }

    /**
     * ⏸️ EL MISMO DEFECTO, EN LA TABLA DE AL LADO — MEDIDO, NO ARREGLADO.
     *
     * <p>Medido el 2026-09-13 con este mismo método: dos ventas idénticas salvo
     * en {@code order_delivery_tracking.order_id}. La del POS (columna escrita)
     * encuentra su seguimiento; la de la nube (columna vacía) lo pierde, porque
     * este servicio busca esa fila por {@code order_id}
     * ({@code OrderDeliveryTracking.java:13-15}) cuando la clave real es
     * {@code order_id_uuid} ({@code V2__multitenant_remaining_tables.sql:12}) y
     * el camino de la nube no escribe {@code order_id}
     * ({@code PostgresOrderCloudSyncAdapter.upsertDeliveryTracking}).
     *
     * <p><b>Por qué se queda apagada en vez de arreglarse.</b> Porque hoy no lo
     * lee nadie a través de este servicio: {@code OrderResponse} no lleva
     * {@code delivered} ni {@code preparationDurationSeconds}, y
     * {@code grep -rn OrderDeliveryTracking src/main/java} solo devuelve la
     * entidad y el campo de {@code Order}. El POS sí muestra «T. Prep», pero lo
     * pide a {@code ms-order-product-mt} ({@code /orders/historial}), que mapea
     * esta misma tabla BIEN —su {@code @Id} está en {@code order_id_uuid}—, así
     * que no le afecta.
     *
     * <p><b>Cuándo hay que encenderla:</b> el día que {@code OrderResponse}
     * empiece a llevar «Entregado» o «Tiempo de preparación» al panel. Ese día
     * esta prueba se pone roja sola y dice qué arreglar. El arreglo no es de una
     * línea como el de {@code OrderItem}: hay que mover el {@code @Id} a
     * {@code order_id_uuid} y rehacer el {@code @MapsId}, que hoy cuelga de
     * {@code order_id} contra {@code Order.id_order}.
     */
    @Test
    @org.junit.jupiter.api.Disabled("Medido y real, pero hoy ningún consumidor lo lee a través del core. "
            + "Encender cuando OrderResponse exponga «Entregado» o «T. Prep» al panel.")
    @DisplayName("⏸️ el cabo hermano: el seguimiento de entrega de una venta de la nube (`order_id` vacío) se pierde")
    void elSeguimientoDeEntregaDeLaNube() {
        // Tal como lo escribe la nube: `PostgresOrderCloudSyncAdapter.upsertDeliveryTracking`
        // pone `order_id_uuid`, `delivered`, `pager_returned` y los segundos —
        // pero NO `order_id`, que es por donde lo busca este servicio
        // (`OrderDeliveryTracking.java:13-15`, @Id sobre `order_id`).
        UUID deLaNube = UUID.randomUUID();
        crearVenta(17, deLaNube, new BigDecimal("30000"));
        crearSeguimiento(deLaNube, null, true, 420);

        // Y tal como lo escribe el POS por JPA (`OrderHandler.java:339-340`,
        // `909-918`): ahí sí se asigna `order_id`.
        UUID delPos = UUID.randomUUID();
        crearVenta(18, delPos, new BigDecimal("30000"));
        crearSeguimiento(delPos, 18L, true, 420);

        assertThat(contarSeguimientosEnLaBase()).as("las dos filas están en la base").isEqualTo(2);

        assertThat(laVentaEnCrudo(18).getDeliveryTracking())
                .as("venta del POS: el seguimiento se encuentra")
                .isNotNull();
        assertThat(laVentaEnCrudo(17).getDeliveryTracking())
                .as("venta de la nube: si esto es null, «Entregado» y «T. Prep» se pierden igual "
                        + "que se perdían las líneas")
                .isNotNull();
    }

    // ---------------------------------------------------------------- apoyo

    /** La orden como entidad, para mirar lo que el DTO del listado no lleva. */
    private com.suresell.mscoreapp.domain.model.Order laVentaEnCrudo(long numeroDeOrden) {
        return new TransactionTemplate(gestorDeTransacciones)
                .execute(estado -> ordenes.findById(numeroDeOrden).orElseThrow());
    }

    private Integer contarSeguimientosEnLaBase() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM order_delivery_tracking WHERE tenant_id = ?",
                Integer.class, NEGOCIO);
    }

    private void crearSeguimiento(UUID ordenUuid, Long orderId, boolean entregado, Integer segundos) {
        jdbc.update("""
                INSERT INTO order_delivery_tracking (order_id_uuid, tenant_id, order_id,
                                                     delivered, pager_returned, preparation_duration_seconds)
                VALUES (?, ?, ?, ?, false, ?)
                """, ordenUuid, NEGOCIO, orderId, entregado, segundos);
    }

    /**
     * El listado real de {@code GET /api/orders}, filtrado por número de orden:
     * el mismo caso de uso, el mismo repositorio y el mapper generado de
     * verdad. Lo único postizo es el catálogo de productos, que solo pone el
     * nombre visible y no interviene en si las líneas llegan o no.
     *
     * <p>Se ejecuta DENTRO de una transacción porque así corre en producción
     * ({@code ManageOrderUseCase} es {@code @Transactional(readOnly = true)}).
     * Es deliberado: deja fuera la explicación fácil —«será la carga
     * perezosa»— y obliga a que lo que falle sea otra cosa.
     */
    private OrderResponse laVentaComoLaVeElPanel(long numeroDeOrden) {
        ManageOrderUseCase listado = new ManageOrderUseCase(
                new OrderRepositoryImpl(ordenes), catalogoVacio(), new OrderMapperImpl());

        Page<OrderResponse> pagina = new TransactionTemplate(gestorDeTransacciones)
                .execute(estado -> listado.getOrders(numeroDeOrden, null, null, PageRequest.of(0, 10)));

        assertThat(pagina.getContent())
                .as("la venta #%s aparece en el listado", numeroDeOrden)
                .hasSize(1);
        return pagina.getContent().get(0);
    }

    private Integer contarLineasEnLaBase(long numeroDeOrden) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM order_item WHERE order_id = ? AND tenant_id = ? AND order_id IS NOT NULL",
                Integer.class, numeroDeOrden, NEGOCIO);
    }

    private void crearVenta(long numeroDeOrden, UUID uuid, BigDecimal total) {
        jdbc.update("""
                INSERT INTO orders (uuid_id, tenant_id, id_order, created_at, status,
                                    payment_method, subtotal, total, synced, is_printed)
                VALUES (?, ?, ?, now(), 'pagado', 'CASH', ?, ?, true, false)
                """, uuid, NEGOCIO, numeroDeOrden, total, total);
    }

    /** Una línea tal como la dejan los dos caminos de escritura de -mt. */
    private void crearLinea(UUID ordenUuid, long numeroDeOrden, Long idOrderItem,
                            String producto, int cantidad, BigDecimal total) {
        jdbc.update("""
                INSERT INTO order_item (uuid_id, tenant_id, id_order_item, order_id, order_uuid_id,
                                        product_id, quantity, unit_price, total_price)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), NEGOCIO, idOrderItem, numeroDeOrden, ordenUuid,
                producto, cantidad, total.divide(BigDecimal.valueOf(cantidad)), total);
    }

    /**
     * El catálogo no es parte de lo que se mide: el nombre del producto se
     * resuelve aparte y, cuando no está, el caso de uso escribe «Producto
     * desconocido». Las líneas llegan o no llegan antes de eso.
     */
    private MenuProductRepository catalogoVacio() {
        return new MenuProductRepository() {
            @Override
            public Page<MenuProductEntity> findAll(Pageable pageable) {
                return Page.empty();
            }

            @Override
            public Optional<MenuProductEntity> findById(String id) {
                return Optional.empty();
            }

            @Override
            public Page<MenuProductEntity> findByCategoryId(String categoryId, Pageable pageable) {
                return Page.empty();
            }

            @Override
            public MenuProductEntity save(MenuProductEntity product) {
                throw new UnsupportedOperationException("el listado no escribe productos");
            }

            @Override
            public void deleteById(String id) {
                throw new UnsupportedOperationException("el listado no borra productos");
            }

            @Override
            public boolean existsById(String id) {
                return false;
            }
        };
    }
}
