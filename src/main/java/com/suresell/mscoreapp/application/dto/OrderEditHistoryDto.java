package com.suresell.mscoreapp.application.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderEditHistoryDto {
    private Long id;
    private Long orderId;
    /** Texto tal cual de la base: quien lo escribe es otro servicio. */
    private String editType;
    private String productId;
    private String productName;
    private Integer oldQuantity;
    private Integer newQuantity;
    private BigDecimal oldTotal;
    private BigDecimal newTotal;
    private String discountCode;
    private LocalDateTime editedAt;
}
