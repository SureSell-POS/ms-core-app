package com.suresell.mscoreapp.infrastructure.web.adapter;

import com.suresell.mscoreapp.application.dto.*;
import com.suresell.mscoreapp.application.usecase.AccountReceivableService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Cartera vieja. Plan de mayoristas F4.6: <b>las escrituras están retiradas</b> y
 * responden 410 {@code MODULO_RETIRADO} sin leer el cuerpo ni tocar la base. Los
 * abonos, el cupo y el cierre de cuentas se hacen ahora en Cuentas por cobrar del
 * panel, contra {@code ms-order-product-mt /api/cartera}, que lleva el libro por
 * documento (recibos con número, aplicación a facturas). Las lecturas siguen
 * hasta retirar la pantalla vieja.
 *
 * <p>Medido antes de retirarlas (2026-09-14, grep en todas las ramas remotas de
 * panel, POS, KAM, cocina, mesas, web POS y servicios): ningún cliente llamaba a
 * estas escrituras. El panel solo tenía la ruta con un andamio vacío.
 *
 * <p>🔴 Acoplamiento con producción: esto no puede llegar a producción antes que
 * el panel nuevo de cartera (F4.7), o un comercio se queda sin forma de registrar abonos.
 */
@RestController
@RequestMapping("/api/accounts-receivable")
public class AccountReceivableController {

    static final String RETIRADA = "Los abonos y la cartera se registran ahora en Cuentas por cobrar del panel";

    private static com.suresell.mscoreapp.shared.exception.ModuloRetiradoException retirada() {
        return new com.suresell.mscoreapp.shared.exception.ModuloRetiradoException(RETIRADA);
    }

    private static final Logger logger = LoggerFactory.getLogger(AccountReceivableController.class);

    private final AccountReceivableService service;

    public AccountReceivableController(AccountReceivableService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<?> createAccount() {
        throw retirada();
    }

    @PostMapping("/add-debt")
    public ResponseEntity<?> addDebt() {
        throw retirada();
    }

    @PostMapping("/make-payment")
    public ResponseEntity<?> makePayment() {
        throw retirada();
    }

    @GetMapping("/customer/{customerDocument}")
    public ResponseEntity<?> getAccountByDocument(@PathVariable("customerDocument") String customerDocument) {
        logger.info("🔍 GET /api/accounts-receivable/customer/{} - Consultando cuenta", customerDocument);

        try {
            AccountReceivableDto account = service.getAccountByDocument(customerDocument);
            logger.info("Cuenta encontrada: {}", customerDocument);
            return ResponseEntity.ok(account);
        } catch (IllegalArgumentException e) {
            logger.warn("Cliente no encontrado: {}", customerDocument);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error consultando cuenta: {}", customerDocument, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/with-debt")
    public ResponseEntity<?> getAccountsWithDebt() {
        logger.info("📋 GET /api/accounts-receivable/with-debt - Consultando cuentas con deuda");

        try {
            List<AccountReceivableDto> accounts = service.getAccountsWithDebt();
            logger.info("{} cuentas con deuda encontradas", accounts.size());
            return ResponseEntity.ok(accounts);
        } catch (Exception e) {
            logger.error("Error consultando cuentas con deuda", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/overdue")
    public ResponseEntity<?> getOverdueAccounts(@RequestParam(value = "days", defaultValue = "30") int days) {
        logger.info("⏰ GET /api/accounts-receivable/overdue?days={} - Cuentas vencidas", days);

        try {
            List<AccountReceivableDto> accounts = service.getOverdueAccounts(days);
            logger.info("{} cuentas vencidas encontradas", accounts.size());
            return ResponseEntity.ok(accounts);
        } catch (IllegalArgumentException e) {
            logger.warn("Parámetro de días inválido: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error consultando cuentas vencidas", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<?> getAccountsByStatus(@PathVariable("status") String status) {
        logger.info("GET /api/accounts-receivable/status/{} - Cuentas por estado", status);

        try {
            List<AccountReceivableDto> accounts = service.getAccountsByStatus(status);
            logger.info("{} cuentas con estado {} encontradas", accounts.size(), status);
            return ResponseEntity.ok(accounts);
        } catch (IllegalArgumentException e) {
            logger.warn("Estado inválido: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error consultando cuentas vencidas", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PutMapping("/customer/{customerDocument}/close")
    public ResponseEntity<?> closeAccount() {
        throw retirada();
    }

    @PutMapping("/customer/{customerDocument}/credit-limit")
    public ResponseEntity<?> updateCreditLimit() {
        throw retirada();
    }

    @GetMapping("/customer/{customerDocument}/transactions")
    public ResponseEntity<?> getTransactionHistory(@PathVariable("customerDocument") String customerDocument,
                                                   @RequestParam(value = "startDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                                   @RequestParam(value = "endDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        logger.info("GET /api/accounts-receivable/customer/{}/transactions - Historial", customerDocument);

        try {
            List<DebtTransactionDto> transactions = service.getTransactionHistory(customerDocument, startDate, endDate);
            logger.info("{} transacciones encontradas para: {}", transactions.size(), customerDocument);
            return ResponseEntity.ok(transactions);
        } catch (IllegalArgumentException e) {
            logger.warn("Error en historial de transacciones: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error obteniendo historial: {}", customerDocument, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/customer/{customerDocument}/debt-amount")
    public ResponseEntity<?> getCustomerDebt(@PathVariable("customerDocument") String customerDocument) {
        logger.info("GET /api/accounts-receivable/customer/{}/debt-amount - Consulta de deuda", customerDocument);

        try {
            BigDecimal debtAmount = service.getCustomerDebt(customerDocument);
            logger.info("Deuda consultada para: {} = ${}", customerDocument, debtAmount);
            return ResponseEntity.ok(Map.of(
                    "customerDocument", customerDocument,
                    "debtAmount", debtAmount
            ));
        } catch (Exception e) {
            logger.error("Error consultando deuda: {}", customerDocument, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/statistics")
    public ResponseEntity<?> getStatistics() {
        logger.info("GET /api/accounts-receivable/statistics - Estadísticas generales");

        try {
            AccountStatsResponse stats = service.getStatistics();
            logger.info("Estadísticas generadas exitosamente");
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            logger.error("Error generando estadísticas", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
