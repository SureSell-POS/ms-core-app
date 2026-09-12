package com.suresell.mscoreapp.infrastructure.persistence;

import com.suresell.mscoreapp.domain.model.OrderEditHistoryEntity;
import com.suresell.mscoreapp.infrastructure.multitenant.TenantContext;
import com.suresell.mscoreapp.infrastructure.persistence.jpa.OrderEditHistoryJpaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El historial de cambios lo ESCRIBE ms-order-product y lo LEE este servicio.
 *
 * <p>Hasta el 2026-09-12 la columna `edit_type` se convertía a un enum de tres
 * valores. En cuanto el otro servicio escribió uno nuevo (`DISCOUNT_APPLIED`, su
 * V57), Hibernate no pudo convertir esa fila y reventaba la consulta ENTERA: la
 * pantalla de historial se caía para todas las filas del negocio, incluidas las
 * que sí entendía, y bastaba un descuento para dejarla inservible.
 *
 * <p>Esta prueba fija la regla: un valor que este servicio no conoce se lee como
 * texto y no puede tumbar la lectura de los demás.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// Sin la transaccion envolvente de @DataJpaTest: Hibernate resuelve el negocio AL ABRIR la
// sesion, y esa transaccion se abre antes del @BeforeEach que fija el contexto. Con ella,
// el repositorio leia con el negocio sin fijar y devolvia cero filas siempre.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class HistorialLeeTiposNuevosTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("esquema-historial-de-edicion.sql");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired
    OrderEditHistoryJpaRepository repositorio;

    @Autowired
    JdbcTemplate jdbc;

    /** El negocio de la prueba: `EntidadDeNegocio` lleva `@TenantId`, así que Hibernate
     *  añade `AND tenant_id = ?` a toda lectura. Sin esto, el repositorio no ve nada. */
    private static final String NEGOCIO = "qa-ferreteria";

    @BeforeEach
    void fijarNegocio() {
        TenantContext.set(NEGOCIO);
        // Sin transaccion envolvente no hay rollback: cada caso empieza con la tabla limpia.
        jdbc.update("DELETE FROM order_edit_history");
    }

    @AfterEach
    void limpiarNegocio() {
        // El ThreadLocal sobrevive al test y JUnit reutiliza el hilo.
        TenantContext.clear();
    }

    @Test
    @DisplayName("🔴 un tipo que este servicio no conoce se lee, y no tumba las filas que sí conoce")
    void tipoDesconocidoNoRompeLaLectura() {
        jdbc.update("INSERT INTO order_edit_history (tenant_id, order_id, edit_type, product_id, product_name, "
                + "old_quantity, new_quantity, edited_at) VALUES (?, 77, 'ITEM_ADDED', 'p-1', 'Martillo', 0, 1, now())", NEGOCIO);
        jdbc.update("INSERT INTO order_edit_history (tenant_id, order_id, edit_type, old_total, new_total, "
                + "discount_code, edited_by, edited_at) VALUES (?, 77, 'DISCOUNT_APPLIED', 20000, 18000, "
                + "'PROMO10', 'admin@negocio', now())", NEGOCIO);
        // Un valor que nadie ha inventado todavía: mañana habrá otro, y tampoco debe romper.
        jdbc.update("INSERT INTO order_edit_history (tenant_id, order_id, edit_type, edited_at) "
                + "VALUES (?, 77, 'ALGO_QUE_NO_EXISTE_HOY', now())", NEGOCIO);

        List<OrderEditHistoryEntity> filas = repositorio.findAll();

        assertThat(filas).hasSize(3);
        assertThat(filas).extracting(OrderEditHistoryEntity::getEditType)
                .containsExactlyInAnyOrder("ITEM_ADDED", "DISCOUNT_APPLIED", "ALGO_QUE_NO_EXISTE_HOY");

        // Dos afirmaciones, no una: con la tabla vacia un cero tambien seria "verde".
        // El negocio de al lado no ve ninguna de estas filas.
        TenantContext.set("otro-negocio");
        assertThat(repositorio.findAll()).isEmpty();
        TenantContext.set(NEGOCIO);
        assertThat(repositorio.findAll()).hasSize(3);
    }

    @Test
    @DisplayName("el cupón del descuento llega hasta quien lo lee")
    void elCuponSeLee() {
        jdbc.update("INSERT INTO order_edit_history (tenant_id, order_id, edit_type, old_total, new_total, "
                + "discount_code, edited_at) VALUES (?, 78, 'DISCOUNT_APPLIED', 20000, 18000, 'PROMO10', now())", NEGOCIO);

        OrderEditHistoryEntity fila = repositorio.findAll().stream()
                .filter(f -> f.getOrderId() == 78L).findFirst().orElseThrow();

        assertThat(fila.getDiscountCode()).isEqualTo("PROMO10");
        assertThat(fila.getEditType()).isEqualTo("DISCOUNT_APPLIED");
    }
}
