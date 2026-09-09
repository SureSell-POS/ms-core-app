package com.suresell.mscoreapp.infrastructure.multitenant;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Exige un JWT válido y deja el tenant en {@link TenantContext}.
 *
 * <p><b>Por qué existe.</b> Hasta ahora este servicio no pedía autenticación:
 * cualquiera con la URL podía leer los gastos, la nómina y las ventas del
 * negocio desde internet (verificado: 212 gastos con un GET sin cabeceras).
 * Además, al no saber quién preguntaba, no había forma de servir a más de un
 * negocio.
 *
 * <p>El token es el MISMO que emite `ms-order-product` al iniciar sesión en el
 * panel, así que no hay un segundo sistema de credenciales que mantener.
 */
@Component
public class JwtTenantFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtTenantFilter.class);

    private final SecretKey key;

    public JwtTenantFilter(@Value("${security.jwt.secret:}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "Falta security.jwt.secret (variable JWT_SECRET). Sin ella este servicio "
                            + "no puede validar tokens y quedaria abierto a internet.");
        }
        this.key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * La ÚNICA ruta exenta: la sonda de salud.
     *
     * <p><b>Coincidencia exacta, no prefijo, y por una razón concreta.</b> La
     * versión anterior eximía {@code /actuator} entero, más {@code /swagger-ui} y
     * {@code /v3/api-docs}. Nunca llegó a aplicarse —ver {@link #esPublica}— así
     * que el efecto real de arreglar la comparación habría sido abrir de golpe
     * todo lo que colgara de {@code /actuator} en un servicio que maneja nómina,
     * cartera y gastos. Hoy solo está expuesto {@code health}, pero eso es una
     * línea de configuración que alguien puede cambiar sin pensar en este filtro.
     *
     * <p>Con coincidencia exacta, exponer un endpoint nuevo de actuator **no** lo
     * hace público por accidente: hay que venir aquí a decidirlo.
     *
     * <p><b>Swagger y la especificación OpenAPI quedan fuera a propósito.</b>
     * Llevan cerrados desde el 2026-07-30 sin que nadie lo echara en falta, y
     * publicar el mapa completo de endpoints de este servicio en internet no
     * tiene contrapartida: quien lo necesita para desarrollar lo tiene en local,
     * donde este filtro exige un token de juguete y no un secreto de producción.
     */
    private static final String RUTA_DE_SALUD = "/actuator/health";

    /**
     * @param ruta debe venir de {@code getServletPath()}, NO de
     *     {@code getRequestURI()}. Esa confusión es justo lo que este cambio
     *     arregla: {@code getRequestURI()} incluye el context path, así que la
     *     ruta real era {@code /api/core/actuator/health} y no empezaba por
     *     {@code /actuator}. La exención no se cumplió ni una vez desde que se
     *     escribió, y el health check quedó devolviendo 401 en los dos entornos.
     */
    private static boolean esPublica(String ruta) {
        return RUTA_DE_SALUD.equals(ruta);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        if (esPublica(req.getServletPath()) || "OPTIONS".equalsIgnoreCase(req.getMethod())) {
            chain.doFilter(req, res);
            return;
        }

        String cabecera = req.getHeader("Authorization");
        if (cabecera == null || !cabecera.startsWith("Bearer ")) {
            // Al cliente, lo justo. Al log, el motivo: fue la lección del 401
            // que costó diez minutos (2026-07-30, el health check).
            log.warn("401 en {} {}: {}", req.getMethod(), req.getRequestURI(),
                    cabecera == null ? "sin cabecera Authorization" : "cabecera sin el prefijo Bearer");
            rechazar(res, HttpServletResponse.SC_UNAUTHORIZED, "SESION_REQUERIDA",
                    "Falta el token de sesion. Vuelve a iniciar sesion.");
            return;
        }

        String tenantId;
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(cabecera.substring(7).trim())
                    .getPayload();
            // El emisor usa `tenant_id`. Se acepta tambien `tenantId` por si
            // alguna version del token lo escribe en camelCase.
            tenantId = claims.get("tenant_id", String.class);
            if (tenantId == null) {
                tenantId = claims.get("tenantId", String.class);
            }
        } catch (Exception e) {
            // Ambiguo para el cliente a propósito (no se le cuenta a un atacante
            // si fue la firma o la fecha); completo en el log, sin el token.
            log.warn("401 en {} {}: token rechazado ({}: {})", req.getMethod(), req.getRequestURI(),
                    e.getClass().getSimpleName(), primeraLinea(e.getMessage()));
            rechazar(res, HttpServletResponse.SC_UNAUTHORIZED, "SESION_INVALIDA",
                    "Sesion invalida o vencida. Vuelve a iniciar sesion.");
            return;
        }

        if (tenantId == null || tenantId.isBlank()) {
            // Un token sin tenant (super-admin del KAM) no puede leer datos de
            // negocio: no sabriamos de cual.
            log.warn("403 en {} {}: el token no identifica un negocio", req.getMethod(), req.getRequestURI());
            rechazar(res, HttpServletResponse.SC_FORBIDDEN, "SIN_NEGOCIO",
                    "El token no identifica un negocio. Entra con la cuenta del negocio, no con la del KAM.");
            return;
        }

        try {
            TenantContext.set(tenantId);
            chain.doFilter(req, res);
        } finally {
            // Imprescindible: el hilo vuelve al pool y sin esto arrastraria el
            // tenant al siguiente request.
            TenantContext.clear();
        }
    }

    /**
     * Responde en JSON con el mismo formato que {@code TraductorDeErrores}
     * ({@code error} + {@code mensaje}). Antes era {@code sendError}, que en un
     * filtro produce la página de error de Spring sin mensaje: el panel veía un
     * 401 mudo y no podía distinguir «no hay sesión» de «venció».
     */
    private static void rechazar(HttpServletResponse res, int estado, String codigo, String mensaje)
            throws java.io.IOException {
        res.setStatus(estado);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"error\":\"" + codigo + "\",\"mensaje\":\"" + mensaje + "\"}");
    }

    private static String primeraLinea(String m) {
        if (m == null) {
            return "";
        }
        int corte = m.indexOf('\n');
        return (corte < 0 ? m : m.substring(0, corte)).trim();
    }
}
