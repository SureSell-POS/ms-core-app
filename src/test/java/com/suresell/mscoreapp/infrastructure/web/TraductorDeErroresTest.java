package com.suresell.mscoreapp.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.Map;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Ola 4 («que funcione»): el core no tenía manejador y devolvía el
 * «Bad Request» pelado de Spring, sin el texto que el DTO ya traía.
 */
class TraductorDeErroresTest {

    private final TraductorDeErrores traductor = new TraductorDeErrores();

    private static DataIntegrityViolationException deLaBase(String sqlState, String mensaje) {
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException(mensaje, new SQLException(mensaje, sqlState), "una_restriccion"));
    }

    @Test
    @DisplayName("una categoría inexistente al crear un producto es 422 y dice qué crear primero")
    void referenciaInexistente() {
        ResponseEntity<Map<String, Object>> r = traductor.deLaBase(deLaBase("23503",
                "ERROR: insert or update on table \"menu_products\" violates foreign key constraint \"fk_categoria\""));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(r.getBody()).containsEntry("error", "NO_EXISTE");
        assertThat(String.valueOf(r.getBody().get("mensaje"))).contains("categoría").doesNotContain("fk_categoria");
    }

    @Test
    @DisplayName("el RAISE de V43 sobre supplies llega entero: es la explicación, no un 500")
    void raiseNuestroViajaTalCual() {
        ResponseEntity<Map<String, Object>> r = traductor.deLaBase(deLaBase("P0001",
                "Los insumos ya no se crean aquí: usa Insumos y Compras.\nWhere: PL/pgSQL function fn_supplies_solo_lectura()"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody()).containsEntry("mensaje", "Los insumos ya no se crean aquí: usa Insumos y Compras.");
    }

    @Test
    @DisplayName("una columna que falta en la base es un defecto nuestro: 500 sin contar nada por dentro")
    void defectoNuestro() {
        ResponseEntity<Map<String, Object>> r = traductor.deLaBase(deLaBase("42703",
                "ERROR: column we1_0.commission_percentage does not exist"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(r.getBody()).containsEntry("error", "ERROR_INTERNO");
        assertThat(String.valueOf(r.getBody().get("mensaje"))).doesNotContain("commission_percentage");
    }

    @Test
    @DisplayName("una ruta que no existe es 404 en JSON, no la página de Tomcat")
    void rutaInexistente() {
        ResponseEntity<Map<String, Object>> r = traductor.rutaInexistente(
                new NoResourceFoundException(HttpMethod.POST, "api/menu/categories"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody()).containsEntry("error", "NO_EXISTE");
    }

    @Test
    @DisplayName("un duplicado es 409, y el nombre de la restricción se queda en el log")
    void duplicado() {
        ResponseEntity<Map<String, Object>> r = traductor.deLaBase(deLaBase("23505",
                "ERROR: duplicate key value violates unique constraint \"menu_categories_pkey\""));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody()).containsEntry("error", "YA_EXISTE");
        assertThat(String.valueOf(r.getBody().get("mensaje"))).doesNotContain("pkey");
    }
}
