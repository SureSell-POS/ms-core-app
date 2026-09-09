package com.suresell.mscoreapp.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * El interruptor del inventario viejo y el traductor, medidos por su
 * comportamiento HTTP y no por su estructura.
 */
class ModuloViejoDeInventarioSoloLecturaTest {

    private MockHttpServletRequest peticion(String metodo, String ruta) {
        MockHttpServletRequest req = new MockHttpServletRequest(metodo, "/api/core" + ruta);
        req.setContextPath("/api/core");
        req.setServletPath(ruta);
        return req;
    }

    @Test
    @DisplayName("apagado (el valor por defecto): no toca nada, ni las escrituras viejas")
    void apagadoNoHaceNada() throws Exception {
        var interceptor = new ModuloViejoDeInventarioSoloLectura(false);
        MockHttpServletResponse res = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(peticion("POST", "/api/supplies"), res, new Object())).isTrue();
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("encendido: una escritura a una ruta vieja responde 410 y dice adónde ir")
    void encendidoCierraLasEscrituras() throws Exception {
        var interceptor = new ModuloViejoDeInventarioSoloLectura(true);
        MockHttpServletResponse res = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(peticion("POST", "/api/weekly-inventory"), res, new Object())).isFalse();
        assertThat(res.getStatus()).isEqualTo(410);
        assertThat(res.getContentAsString()).contains("MODULO_RETIRADO").contains("Insumos y Compras");
    }

    @Test
    @DisplayName("encendido: las lecturas viejas y las rutas nuevas siguen pasando")
    void encendidoDejaPasarLecturasYLoNuevo() throws Exception {
        var interceptor = new ModuloViejoDeInventarioSoloLectura(true);

        for (String[] caso : new String[][] {{"GET", "/api/supplies"}, {"POST", "/api/menu/categories"},
                                             {"DELETE", "/api/expenses/3"}, {"GET", "/api/supplier-requests/9"}}) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            assertThat(interceptor.preHandle(peticion(caso[0], caso[1]), res, new Object()))
                    .as(caso[0] + " " + caso[1]).isTrue();
            assertThat(res.getStatus()).as(caso[0] + " " + caso[1]).isEqualTo(200);
        }
    }

    // ------------------------------------------------------------- traductor

    /** Un controlador mínimo con la misma validación que los DTO reales del menú. */
    @RestController
    static class ControladorDePrueba {
        record Cuerpo(@NotBlank(message = "ID de categoría es obligatorio") String id,
                      @NotBlank(message = "Nombre de categoría es obligatorio") String name) {}

        @PostMapping("/prueba")
        String crear(@jakarta.validation.Valid @RequestBody Cuerpo c) {
            return "ok";
        }
    }

    @Test
    @DisplayName("el texto del DTO llega al cuerpo, con el campo: ya no es «Bad Request» pelado")
    void laValidacionDelDtoLlegaConCampo() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ControladorDePrueba())
                .setControllerAdvice(new TraductorDeErrores())
                .build();

        mvc.perform(post("/prueba").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Sin id\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("DATOS_INVALIDOS"))
                .andExpect(jsonPath("$.mensaje").value("ID de categoría es obligatorio"))
                .andExpect(jsonPath("$.campo").value("id"));
    }

    @Test
    @DisplayName("un cuerpo ilegible es 400 con explicación, no una traza de Jackson")
    void cuerpoIlegible() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ControladorDePrueba())
                .setControllerAdvice(new TraductorDeErrores())
                .build();

        mvc.perform(post("/prueba").contentType(MediaType.APPLICATION_JSON).content("{esto no es json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("PETICION_INCOMPLETA"))
                .andExpect(jsonPath("$.mensaje").value(org.hamcrest.Matchers.containsString("no se pudo leer")));
    }
}
