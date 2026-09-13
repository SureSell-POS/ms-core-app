package com.suresell.mscoreapp.infrastructure.persistence;

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
 * EL DETALLE DE LA VENTA TIENE QUE TRAER LO QUE SE COBRÓ Y LO QUE SE REBAJÓ.
 *
 * <h3>Qué sujeta</h3>
 *
 * <p>El panel pinta «Subtotal» y «Descuento» en el detalle de cada venta, y los
 * pinta desde lo que trae el listado: no hay una segunda llamada. El día que
 * {@code OrderResponse} deje de declarar {@code subtotal}, {@code discountCode}
 * o {@code discountAmount}, esos dos bloques se quedan en blanco y NADIE se
 * entera —no hay error, ni log, ni fila de menos, igual que cuando las líneas
 * llegaban vacías—. Esta prueba es la que se entera.
 *
 * <h3>Cómo mide, para que no pueda salir verde en falso</h3>
 *
 * <ol>
 *   <li>Escribe las dos ventas <b>por SQL</b>, con los importes tal como los
 *       deja la nube, y comprueba primero en la base que están: si no, un DTO
 *       con todo a null sería «correcto».</li>
 *   <li>Ejecuta el <b>caso de uso real</b> que sirve {@code GET /api/orders}
 *       ({@link ManageOrderUseCase}) con el mapper generado de verdad, que es
 *       exactamente lo que lee el panel.</li>
 *   <li>Y mide <b>las dos formas de la venta</b>: una con descuento y otra sin
 *       él. La segunda importa tanto como la primera, porque el panel decide
 *       con ella si enseña el bloque o lo calla.</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// Sin la transaccion envolvente de @DataJpaTest: Hibernate resuelve el negocio AL ABRIR la
// sesion, y esa transaccion se abre antes del @BeforeEach que fija el contexto.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class ElDetalleDeLaVentaTraeSusImportesTest {

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
    @DisplayName("una venta con descuento llega al panel con su subtotal, su código y su importe rebajado")
    void laVentaConDescuentoTraeSusTresImportes() {
        // 20.000 de mercancía, 2.000 de rebaja por el código, 18.000 cobrados.
        crearVenta(21, new BigDecimal("20000"), new BigDecimal("18000"),
                "PROMO10", new BigDecimal("2000"));

        // 1) Los importes ESTÁN en la base. Sin esto, un DTO en blanco pasaría.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM orders WHERE id_order = ? AND tenant_id = ? "
                        + "AND subtotal IS NOT NULL AND discount_code IS NOT NULL "
                        + "AND discount_amount IS NOT NULL",
                Integer.class, 21, NEGOCIO))
                .as("la venta está escrita con subtotal, código e importe de descuento")
                .isEqualTo(1);

        // 2) Y el listado que sirve el panel TIENE que traerlos.
        OrderResponse respuesta = laVentaComoLaVeElPanel(21);

        assertThat(respuesta.getSubtotal())
                .as("«Subtotal» del detalle: si no llega, el bloque no se pinta nunca")
                .isNotNull()
                .isEqualByComparingTo("20000");
        assertThat(respuesta.getDiscountCode())
                .as("el código va JUNTO al importe rebajado; sin él el comerciante no sabe por qué se rebajó")
                .isEqualTo("PROMO10");
        assertThat(respuesta.getDiscountAmount())
                .as("«Descuento» del detalle")
                .isNotNull()
                .isEqualByComparingTo("2000");
        assertThat(respuesta.getTotal())
                .as("y lo cobrado sigue siendo lo cobrado")
                .isEqualByComparingTo("18000");
    }

    @Test
    @DisplayName("una venta sin descuento trae su subtotal y deja el descuento vacío: con eso el panel calla el bloque")
    void laVentaSinDescuentoTraeSubtotalYElDescuentoVacio() {
        crearVenta(22, new BigDecimal("20000"), new BigDecimal("20000"), null, null);

        OrderResponse respuesta = laVentaComoLaVeElPanel(22);

        assertThat(respuesta.getSubtotal())
                .as("el subtotal se pinta SIEMPRE, haya habido rebaja o no")
                .isNotNull()
                .isEqualByComparingTo("20000");
        assertThat(respuesta.getDiscountCode())
                .as("no hubo código: el panel cuenta con que llegue vacío para no pintar el bloque")
                .isNull();
        assertThat(respuesta.getDiscountAmount())
                .as("no hubo rebaja")
                .isNull();
        assertThat(respuesta.getTotal()).isEqualByComparingTo("20000");
    }

    // ---------------------------------------------------------------- apoyo

    /**
     * El listado real de {@code GET /api/orders}, filtrado por número de orden:
     * el mismo caso de uso, el mismo repositorio y el mapper generado de
     * verdad. Lo único postizo es el catálogo de productos, que solo pone el
     * nombre visible y no toca los importes de la venta.
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

    /** Una venta tal como la escribe la nube, con sus importes. */
    private void crearVenta(long numeroDeOrden, BigDecimal subtotal, BigDecimal total,
                            String codigoDescuento, BigDecimal importeDescuento) {
        jdbc.update("""
                INSERT INTO orders (uuid_id, tenant_id, id_order, created_at, status,
                                    payment_method, subtotal, total, discount_code,
                                    discount_amount, synced, is_printed)
                VALUES (?, ?, ?, now(), 'pagado', 'CASH', ?, ?, ?, ?, true, false)
                """, UUID.randomUUID(), NEGOCIO, numeroDeOrden, subtotal, total,
                codigoDescuento, importeDescuento);
    }

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
