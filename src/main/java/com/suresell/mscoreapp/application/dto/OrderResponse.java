package com.suresell.mscoreapp.application.dto;

import com.suresell.mscoreapp.domain.model.OrderStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Lo que el panel lee de cada venta en «Historial de Ventas».
 *
 * <p>Los importes del descuento ({@code subtotal}, {@code discountCode},
 * {@code discountAmount}) son columnas de la MISMA fila de {@code orders}
 * —{@code Order.java}: {@code subtotal}, {@code discount_code},
 * {@code discount_amount}—, así que salen en la consulta que ya se hace y
 * MapStruct los copia por nombre. Ni consulta nueva, ni migración.
 *
 * <p>Lo que NO lleva y no es olvido: el tiempo de preparación y «entregado»
 * viven en {@code order_delivery_tracking}, otra tabla, y el POS ya los muestra
 * pidiéndoselos a {@code ms-order-product-mt}. {@code synced} es un detalle de
 * la caja, no del comercio.
 */
@Data
@Builder
public class OrderResponse {
    private Long idOrder;
    private String pagerColor;
    private String pagerNumber;
    private LocalDateTime createdAt;
    private LocalDateTime deliveredAt;
    private BigDecimal subtotal;
    private BigDecimal total;
    private String discountCode;
    private BigDecimal discountAmount;
    private OrderStatus status;
    private String paymentMethod;
    private List<OrderItemDto> items;
}
