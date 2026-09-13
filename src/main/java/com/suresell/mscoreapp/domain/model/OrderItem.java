package com.suresell.mscoreapp.domain.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
@Entity
@Table(name = "order_item")  
@Data
// tenant_id no entra en equals: todas las filas de una sesion comparten negocio.
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
public class OrderItem extends EntidadDeNegocio {
    /**
     * La clave primaria de {@code order_item} es el UUID, no el número.
     *
     * <p>Hasta el 2026-09-13 aquí estaba {@code id_order_item} con
     * {@code @GeneratedValue(IDENTITY)}. Era falso en los dos sentidos:
     *
     * <ul>
     *   <li>La clave primaria de la tabla es {@code uuid_id}
     *       ({@code ms-order-product-mt V1__multitenant_baseline.sql:30}).</li>
     *   <li>{@code id_order_item} es un {@code BIGINT} suelto —{@code V1:32}—
     *       <b>sin secuencia, sin DEFAULT y sin trigger</b>, y no lo escribe
     *       ninguno de los dos caminos que crean líneas: ni el del POS
     *       ({@code OrderHandler.java:1183-1198}) ni el de la nube
     *       ({@code PostgresOrderCloudSyncAdapter.java:196-210}). En la nube
     *       viene NULL siempre.</li>
     * </ul>
     *
     * <p><b>Qué provocaba.</b> Al cargar la colección, Hibernate no puede
     * construir una entidad cuya clave primaria es NULL y descarta la fila. La
     * venta se listaba con su total correcto y sin una sola línea, y el panel
     * mostraba «Esta venta no trae sus artículos». Sin error, sin log y sin
     * fila de menos: la consulta traía las líneas y se perdían al mapearlas.
     *
     * <p>Por eso la medición en la base decía la verdad —{@code order_id}
     * poblado en las 134 líneas— y la pantalla también: lo que fallaba no era
     * la columna por la que se unen, sino la que identifica la fila.
     *
     * <p>Lo fija {@code ElDetalleDeLaVentaTraeSusLineasTest}, que contrasta dos
     * ventas idénticas salvo en {@code id_order_item}.
     */
    @Id
    @Column(name = "uuid_id", nullable = false)
    private java.util.UUID uuidId;

    /**
     * El número de línea del POS local, donde sí es la clave. En la nube nadie
     * lo escribe, así que aquí viene NULL casi siempre: se mapea para poder
     * leerlo cuando esté, nunca para identificar la fila.
     */
    @Column(name = "id_order_item")
    private Long idOrderItem;
    @ManyToOne(fetch = FetchType.LAZY)  
    @JoinColumn(name = "order_id", nullable = false)  
    private Order order;
    @Column(name = "product_id")
    private String productId;
    private int quantity;
    @Column(name = "unit_price")
    private BigDecimal unitPrice;
    @Column(name = "total_price")
    private BigDecimal totalPrice;
    private String instructions;
    @Column(name = "combo_group")
    private Integer comboGroup;
}
