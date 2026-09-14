package com.suresell.mscoreapp.infrastructure.web.adapter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.suresell.mscoreapp.application.usecase.AccountReceivableService;
import com.suresell.mscoreapp.infrastructure.web.TraductorDeErrores;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Plan de mayoristas F4.6: las escrituras de la cartera vieja responden 410
 * {@code MODULO_RETIRADO} y NO llegan al servicio, que es el único camino a
 * {@code accounts_receivable} y {@code debt_transactions} desde este servicio
 * (grep: nadie más llama a createAccount, addDebt, makePayment, closeAccount ni
 * updateCreditLimit). Si el servicio no se toca, no hay fila nueva posible.
 * Las lecturas siguen.
 */
class CarteraViejaSinEscriturasTest {

    private AccountReceivableService servicio;
    private MockMvc mockMvc;

    @BeforeEach
    void preparar() {
        servicio = mock(AccountReceivableService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AccountReceivableController(servicio))
                .setControllerAdvice(new TraductorDeErrores())
                .build();
    }

    private void retirada(MockHttpServletRequestBuilder peticion) throws Exception {
        mockMvc.perform(peticion.contentType(MediaType.APPLICATION_JSON)
                        // Un cuerpo válido de verdad: el 410 no depende de que el cuerpo esté mal.
                        .content("{\"customerDocument\":\"900123456\",\"customerName\":\"Tienda\",\"amount\":20000,"
                                + "\"creditLimit\":100000,\"reason\":\"x\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.codigo").value("MODULO_RETIRADO"))
                .andExpect(jsonPath("$.error").value("MODULO_RETIRADO"))
                .andExpect(jsonPath("$.mensaje").value("Los abonos y la cartera se registran ahora en Cuentas por cobrar del panel"))
                .andExpect(jsonPath("$.message").value("Los abonos y la cartera se registran ahora en Cuentas por cobrar del panel"));
    }

    @Test
    @DisplayName("🔴 las cinco escrituras dan 410 MODULO_RETIRADO y no tocan el servicio (ni la base)")
    void escriturasRetiradas() throws Exception {
        retirada(post("/api/accounts-receivable"));
        retirada(post("/api/accounts-receivable/add-debt"));
        retirada(post("/api/accounts-receivable/make-payment"));
        retirada(put("/api/accounts-receivable/customer/900123456/close"));
        retirada(put("/api/accounts-receivable/customer/900123456/credit-limit"));
        // Tampoco con el cuerpo vacío o roto: el recurso ya no está, sea lo que sea que llegue.
        mockMvc.perform(post("/api/accounts-receivable/make-payment")).andExpect(status().isGone());
        verifyNoInteractions(servicio);
    }

    @Test
    @DisplayName("las lecturas siguen dando 200 hasta retirar la pantalla vieja")
    void lecturasSiguen() throws Exception {
        when(servicio.getAccountsWithDebt()).thenReturn(List.of());
        when(servicio.getCustomerDebt(any())).thenReturn(new BigDecimal("20000"));
        mockMvc.perform(get("/api/accounts-receivable/with-debt")).andExpect(status().isOk());
        mockMvc.perform(get("/api/accounts-receivable/customer/900123456/debt-amount"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.debtAmount").value(20000));
    }
}
