package com.suresell.mscoreapp.domain.model;

import com.suresell.mscoreapp.shared.enums.OrderEditType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "order_edit_history")
public class OrderEditHistoryEntity extends EntidadDeNegocio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /**
     * Tipo de cambio, TAL CUAL está en la base y sin convertir a enum.
     *
     * <p>Hasta el 2026-09-12 esto era un {@code @Enumerated(EnumType.STRING)} sobre
     * {@link OrderEditType}, que solo conocía tres valores. En cuanto
     * ms-order-product escribió {@code DISCOUNT_APPLIED} (V57), Hibernate no pudo
     * convertir esa fila y la consulta entera fallaba: la pantalla de historial se
     * caía para TODAS las filas del negocio, incluidas las que sí entendía. Esta
     * tabla la escribe otro servicio, así que aquí se lee como texto y ningún valor
     * nuevo puede volver a tumbar la lectura.
     */
    @Column(name = "edit_type")
    private String editType;

    /** Cupón aplicado, cuando el cambio fue un descuento (ms-order-product, V57). */
    @Column(name = "discount_code")
    private String discountCode;

    @Column(name = "product_id")
    private String productId;

    @Column(name = "product_name")
    private String productName;

    @Column(name = "old_quantity")
    private Integer oldQuantity;

    @Column(name = "new_quantity")
    private Integer newQuantity;

    @Column(name = "old_total", precision = 38, scale = 2)
    private BigDecimal oldTotal;

    @Column(name = "new_total", precision = 38, scale = 2)
    private BigDecimal newTotal;

    @Column(name = "edited_at")
    private LocalDateTime editedAt;

    @PrePersist
    public void prePersist() {
        if (this.editedAt == null) {
            this.editedAt = LocalDateTime.now();
        }
    }
}
