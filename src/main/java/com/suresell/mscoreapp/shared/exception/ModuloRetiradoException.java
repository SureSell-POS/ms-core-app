package com.suresell.mscoreapp.shared.exception;

/**
 * Una escritura de un módulo que ya vive en otro servicio. Sale como 410
 * {@code MODULO_RETIRADO}: el recurso existió y no va a volver aquí.
 *
 * <p>Plan de mayoristas F4.6: las escrituras de cartera
 * ({@code /api/accounts-receivable}) pasaron a {@code ms-order-product-mt}
 * ({@code /api/cartera}), que lleva el libro por documento con recibos. Seguir
 * escribiendo aquí dejaría abonos sin aplicar a ninguna factura.
 */
public class ModuloRetiradoException extends RuntimeException {

    public static final String CODIGO = "MODULO_RETIRADO";

    public ModuloRetiradoException(String mensaje) {
        super(mensaje);
    }
}
