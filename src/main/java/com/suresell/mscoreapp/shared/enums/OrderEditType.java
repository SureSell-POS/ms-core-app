package com.suresell.mscoreapp.shared.enums;

public enum OrderEditType {
    ITEM_ADDED,
    ITEM_REMOVED,
    ITEM_QUANTITY_CHANGED,
    /**
     * Descuento aplicado a la venta entera (lo escribe ms-order-product desde V57).
     * No tiene producto: afecta al total, no a una línea.
     */
    DISCOUNT_APPLIED
}
