package com.suresell.mscoreapp.infrastructure.web;

import com.suresell.mscoreapp.shared.exception.SupplyNotFoundException;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Convierte lo que falla dentro en algo que una persona pueda leer.
 *
 * <h2>Lo que había (fase 0, 2026-09-09)</h2>
 *
 * Nada. Este servicio no tenía ningún manejador, así que Spring respondía lo
 * suyo: un {@code POST /api/menu/categories} sin {@code id} salía como
 * <pre>{"status":400,"error":"Bad Request","path":"/api/core/api/menu/categories"}</pre>
 * aunque el DTO tenía escrito «ID de categoría es obligatorio». El texto
 * existía y no llegaba a nadie. Y una columna que faltaba en la base
 * ({@code waiters.commission_percentage} en staging) salía como
 * {@code Internal Server Error} en HTML, sin JSON que el panel pudiera leer.
 *
 * <h2>Tres cosas distintas, que no se mezclan</h2>
 *
 * <ul>
 *   <li><b>El mensaje</b> ({@code mensaje}): para quien está en el panel. Qué
 *       pasó y, cuando se puede, qué hacer. Nunca una traza ni el nombre de
 *       una restricción de la base.
 *   <li><b>El código</b> ({@code error}): estable, para que el cliente decida
 *       sin leer el texto. Y {@code campo} cuando el rechazo es de un campo
 *       concreto, para pintarlo junto al input.
 *   <li><b>El log</b>: técnico y completo. Rechazo de negocio a INFO, petición
 *       mal formada a WARN, defecto nuestro a ERROR con la traza.
 * </ul>
 *
 * <p>Mismo formato que {@code TraductorDeErrores} del inventario y que
 * {@code GlobalExceptionHandler} de {@code ms-order-product-mt} (ese usa
 * {@code message}; el panel y el POS leen las dos claves).
 */
@RestControllerAdvice
public class TraductorDeErrores {

    private static final Logger log = LoggerFactory.getLogger(TraductorDeErrores.class);

    /** Un RAISE EXCEPTION nuestro: escrito para leerse (p. ej. los triggers de V43 sobre `supplies`). */
    private static final String RAISE_NUESTRO = "P0001";
    private static final String CLAVE_DUPLICADA = "23505";
    private static final String REFERENCIA_INEXISTENTE = "23503";
    private static final String REGLA_INCUMPLIDA = "23514";
    private static final String NO_NULO = "23502";

    /**
     * El cuerpo del error, con el código bajo LAS DOS claves.
     *
     * <p>🔴 `codigo` no es un adorno: es la que lee el panel. Su `leerError`
     * (`core/ui/motivo-del-error.ts:52`) rellena `motivo.codigo` desde
     * {@code codigo} o {@code code}, y de ninguna otra. Este servicio emitía solo
     * {@code error}, así que el panel nunca reconocía el código y caía en su
     * respaldo por estado HTTP: la integración funcionaba por casualidad, no por
     * contrato. `ms-order-product-mt` lleva meses emitiendo {@code codigo} con
     * ese mismo panel; el desviado era este servicio.
     *
     * <p>{@code error} se conserva por si algo ya lo lee. Quitar {@code codigo}
     * rompe el panel en silencio: lo fija `elCuerpoTraeElCodigoQueLeeElPanel`.
     */
    private static Map<String, Object> cuerpo(String codigo, String mensaje) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", codigo);
        m.put("codigo", codigo);
        m.put("mensaje", mensaje);
        return m;
    }

    private static ResponseEntity<Map<String, Object>> respuesta(HttpStatus estado, String codigo, String mensaje) {
        return ResponseEntity.status(estado).body(cuerpo(codigo, mensaje));
    }

    static SQLException sqlDetras(Throwable e) {
        for (Throwable t = e; t != null && t.getCause() != t; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql;
            }
        }
        return null;
    }

    static String soloLaPrimeraLinea(String mensaje) {
        if (mensaje == null) {
            return "";
        }
        int corte = mensaje.indexOf('\n');
        return (corte < 0 ? mensaje : mensaje.substring(0, corte)).trim();
    }

    // ----------------------------------------------------------------- negocio

    /** Un campo del cuerpo no pasa su validación: el texto del DTO, y el campo. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> campoInvalido(MethodArgumentNotValidException e) {
        var errores = e.getBindingResult().getFieldErrors();
        String detalle = errores.stream()
                .map(f -> f.getDefaultMessage() == null ? f.getField() + " no es válido" : f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Faltan datos obligatorios.");
        log.info("Campo inválido: {}", detalle);
        Map<String, Object> m = cuerpo("DATOS_INVALIDOS", detalle);
        if (errores.size() == 1) {
            m.put("campo", errores.get(0).getField());
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(m);
    }

    /** Una regla del negocio con código y campo: 422, y el panel la pinta junto al input. */
    @ExceptionHandler(com.suresell.mscoreapp.shared.exception.ReglaDeNegocioException.class)
    public ResponseEntity<Map<String, Object>> reglaDeNegocio(
            com.suresell.mscoreapp.shared.exception.ReglaDeNegocioException e) {
        log.info("Regla de negocio ({}): {}", e.codigo(), e.getMessage());
        Map<String, Object> m = cuerpo(e.codigo(), e.getMessage());
        if (e.campo() != null) {
            m.put("campo", e.campo());
        }
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(m);
    }

    @ExceptionHandler(SupplyNotFoundException.class)
    public ResponseEntity<Map<String, Object>> noEncontrado(SupplyNotFoundException e) {
        log.info("No encontrado: {}", e.getMessage());
        return respuesta(HttpStatus.NOT_FOUND, "NO_EXISTE", e.getMessage());
    }

    /** Un dato que no vale. El texto lo escribió quien validó, para una persona. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> datoRechazado(IllegalArgumentException e) {
        log.info("Dato rechazado: {}", e.getMessage());
        return respuesta(HttpStatus.BAD_REQUEST, "DATOS_INVALIDOS", e.getMessage());
    }

    /** La petición está bien; lo que no encaja es el estado de los datos. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> noSePuedeTodavia(IllegalStateException e) {
        log.info("Rechazo de negocio: {}", e.getMessage());
        return respuesta(HttpStatus.CONFLICT, "NO_SE_PUEDE_TODAVIA", e.getMessage());
    }

    // ------------------------------------------------------ petición mal hecha

    @ExceptionHandler({
        MissingRequestHeaderException.class,
        MissingServletRequestParameterException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<Map<String, Object>> peticionMalFormada(Exception e) {
        log.warn("Petición mal formada: {}", soloLaPrimeraLinea(e.getMessage()));
        String mensaje = e instanceof HttpMessageNotReadableException
                ? "El cuerpo de la petición no se pudo leer. Revisa el formato de los datos enviados."
                : soloLaPrimeraLinea(e.getMessage());
        return respuesta(HttpStatus.BAD_REQUEST, "PETICION_INCOMPLETA", mensaje);
    }

    /** Una ruta que no existe: 404 en JSON, no la página HTML de Tomcat. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> rutaInexistente(NoResourceFoundException e) {
        log.warn("Ruta inexistente: {}", e.getResourcePath());
        return respuesta(HttpStatus.NOT_FOUND, "NO_EXISTE",
                "Esa ruta no existe en este servicio. Las rutas del panel empiezan por /api/core/api/.");
    }

    // ---------------------------------------------------------------- la base

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> deLaBase(DataAccessException e) {
        if (e instanceof EmptyResultDataAccessException) {
            log.info("Sin fila: {}", soloLaPrimeraLinea(e.getMessage()));
            return respuesta(HttpStatus.NOT_FOUND, "NO_EXISTE",
                    "No hay nada con ese identificador en este negocio.");
        }
        SQLException sql = sqlDetras(e);
        String sqlState = sql == null ? null : sql.getSQLState();
        String detalle = sql == null ? soloLaPrimeraLinea(e.getMessage()) : soloLaPrimeraLinea(sql.getMessage());

        if (RAISE_NUESTRO.equals(sqlState)) {
            // Lo escribió una migración para que lo lea una persona: viaja tal cual.
            log.info("Rechazo de la base (P0001): {}", detalle);
            return respuesta(HttpStatus.CONFLICT, "NO_SE_PUEDE_TODAVIA", detalle);
        }
        if (CLAVE_DUPLICADA.equals(sqlState)) {
            log.warn("Clave duplicada: {}", detalle);
            // Sin «en este negocio»: las claves de menu_categories y
            // menu_products son globales (V2), así que el identificador puede
            // ser de OTRO negocio, y bajo RLS este no lo ve. Decirle que ya lo
            // tiene sería mentira (revisión manual, 2026-09-09).
            return respuesta(HttpStatus.CONFLICT, "YA_EXISTE",
                    "Ese identificador ya está en uso (puede que en otro negocio). Elige otro; "
                    + "el panel pone el nombre del negocio delante para evitarlo.");
        }
        if (REFERENCIA_INEXISTENTE.equals(sqlState)) {
            log.warn("Referencia inexistente: {}", detalle);
            return respuesta(HttpStatus.UNPROCESSABLE_ENTITY, "NO_EXISTE",
                    "Hace referencia a algo que no existe en este negocio (por ejemplo, una categoría "
                    + "que no está creada). Créalo primero o recarga la lista.");
        }
        if (REGLA_INCUMPLIDA.equals(sqlState) || NO_NULO.equals(sqlState)) {
            log.warn("Regla de la base incumplida: {}", detalle);
            return respuesta(HttpStatus.UNPROCESSABLE_ENTITY, "NO_CUMPLE_REGLA",
                    "El dato no cumple una regla del sistema. Queda registrado para revisarlo.");
        }
        log.error("Error de base no previsto (SQLSTATE {})", sqlState, e);
        return respuesta(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_INTERNO",
                "No se pudo completar la operación. Queda registrado para revisarlo.");
    }

    // -------------------------------------------------------------- lo demás

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> loQueNoPrevimos(Exception e) {
        log.error("Error no previsto", e);
        return respuesta(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_INTERNO",
                "No se pudo completar la operación. Queda registrado para revisarlo.");
    }
}
