package com.suresell.mscoreapp.shared.exception;

/**
 * Una regla del negocio que una persona puede arreglar en el formulario, con
 * código estable y campo, para que el panel la pinte junto al input.
 *
 * <p>Nació con la precarga (ola 4): un producto precargado nace a $0 e
 * inactivo, y el botón «Activar» del panel lo activaba igual; se vendía a $0,
 * que es justo lo que el plan quería evitar. Distinta de
 * {@link IllegalStateException} (409 sin campo) en que aquí se sabe el campo.
 */
public class ReglaDeNegocioException extends RuntimeException {

    private final String codigo;
    private final String campo;

    public ReglaDeNegocioException(String codigo, String campo, String mensaje) {
        super(mensaje);
        this.codigo = codigo;
        this.campo = campo;
    }

    public String codigo() {
        return codigo;
    }

    public String campo() {
        return campo;
    }
}
