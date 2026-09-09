package com.suresell.mscoreapp.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Paso «contract» del retiro del inventario viejo: sus escrituras se cierran
 * con un interruptor, y el cierre dice adónde ir.
 *
 * <h2>Qué es «el inventario viejo»</h2>
 *
 * Las tablas {@code supplies}, {@code supply_categories},
 * {@code supply_consumptions}, {@code shopping_items}, {@code supplier_requests}
 * y {@code weekly_inventory_counts} de este servicio, anteriores a
 * {@code inventario.insumos} (ms-smart-inventory). Desde V9 del inventario
 * nuevo la identidad de los 96 insumos de producción vive en los dos sitios, y
 * desde V43 de {@code -mt} la base rechaza crear y borrar en {@code supplies}
 * (medido el 2026-09-09: {@code supply_consumptions} sigue en 0 filas,
 * {@code shopping_items} en 0, {@code weekly_inventory_counts} en 18,
 * {@code supplier_requests} en 4 con 54 líneas; el último cambio en
 * {@code supplies} es del 2026-08-24).
 *
 * <h2>Por qué un interruptor y no un borrado</h2>
 *
 * Todavía hay un cliente vivo que ESCRIBE aquí: la app de cocina
 * ({@code app_mobile_kitchen}) manda el inventario semanal
 * ({@code POST /api/weekly-inventory}), los pedidos a proveedor
 * ({@code POST /api/supplier-requests}) y la lista de compras
 * ({@code POST /api/shopping-list}). Ninguna de esas tres cosas existe todavía
 * en el módulo nuevo. Cerrarlas sin aviso rompería a Shark Burger en cocina.
 *
 * <p>Así que: con {@code INVENTARIO_VIEJO_SOLO_LECTURA=false} (el valor por
 * defecto) esto no hace nada y el despliegue no cambia ningún comportamiento.
 * Con {@code true}, toda escritura a las rutas viejas responde {@code 410 Gone}
 * con un texto que dice qué pantalla usar ahora. Las lecturas siguen: el panel
 * y la app de cocina pueden seguir mostrando lo que hay.
 *
 * <p>Es un interceptor y no un filtro a propósito: corre dentro del
 * {@code DispatcherServlet}, después del filtro de sesión, así que una petición
 * sin token sigue recibiendo su 401 y no un 410 que le contaría que la ruta
 * existe.
 *
 * <p>El orden de retiro (expandir/contraer): 1) el módulo nuevo cubre lo que
 * la cocina escribe aquí, 2) se enciende el interruptor en staging y se mide,
 * 3) en producción, 4) se retiran las rutas y las pantallas (las del panel las
 * quita B, que es dueño del menú), 5) se archivan las tablas.
 */
@Configuration
public class ModuloViejoDeInventarioSoloLectura implements WebMvcConfigurer, HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ModuloViejoDeInventarioSoloLectura.class);

    /**
     * Rutas (sin el context path {@code /api/core}) del módulo viejo que se
     * cierran a escritura con el interruptor.
     *
     * <p><b>No están</b> {@code /api/weekly-inventory}, {@code /api/supplier-requests}
     * ni {@code /api/shopping-list}: son las tres que la app de cocina escribe y
     * que el módulo nuevo no tiene (decisión de Santiago, 2026-09-09: llevarlas
     * al nuevo es grande —proveedores, pedidos con estado, mínimo por insumo y
     * lista generada— y no cabe en esta ola; ver
     * {@code docs/inventario/RETIRO-INVENTARIO-VIEJO.md} §4). Siguen abiertas
     * hasta que el nuevo las cubra. Lo demás del módulo viejo se cierra.
     */
    static final List<String> RUTAS_VIEJAS = List.of(
            "/api/supplies",
            "/api/supply-categories",
            "/api/supply-consumptions");

    /** Lo que la cocina todavía escribe. Se deja pasar a propósito; ver arriba. */
    static final List<String> RUTAS_QUE_LA_COCINA_ESCRIBE = List.of(
            "/api/weekly-inventory",
            "/api/supplier-requests",
            "/api/shopping-list");

    static final String MENSAJE =
            "Este módulo de inventario se retiró. Los insumos, las compras y el stock se llevan ahora en "
            + "«Insumos y Compras» y «Stock y Movimientos» del panel. Lo que ya estaba registrado aquí se "
            + "puede seguir consultando.";

    private final boolean soloLectura;

    public ModuloViejoDeInventarioSoloLectura(
            @Value("${inventario.viejo.solo-lectura:false}") boolean soloLectura) {
        this.soloLectura = soloLectura;
        if (soloLectura) {
            log.warn("El inventario viejo está en SOLO LECTURA: toda escritura a {} responde 410.", RUTAS_VIEJAS);
        }
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this);
    }

    static boolean esEscritura(String metodo) {
        return "POST".equalsIgnoreCase(metodo) || "PUT".equalsIgnoreCase(metodo)
                || "PATCH".equalsIgnoreCase(metodo) || "DELETE".equalsIgnoreCase(metodo);
    }

    static boolean esRutaVieja(String servletPath) {
        if (servletPath == null) {
            return false;
        }
        for (String ruta : RUTAS_VIEJAS) {
            if (servletPath.equals(ruta) || servletPath.startsWith(ruta + "/")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws IOException {
        if (!soloLectura || !esEscritura(req.getMethod()) || !esRutaVieja(req.getServletPath())) {
            return true;
        }
        log.info("Escritura rechazada al inventario viejo: {} {}", req.getMethod(), req.getServletPath());
        res.setStatus(HttpServletResponse.SC_GONE);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"error\":\"MODULO_RETIRADO\",\"mensaje\":\"" + MENSAJE + "\"}");
        return false;
    }
}
