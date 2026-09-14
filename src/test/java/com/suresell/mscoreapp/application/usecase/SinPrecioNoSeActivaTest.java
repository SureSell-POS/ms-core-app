package com.suresell.mscoreapp.application.usecase;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.suresell.mscoreapp.domain.model.MenuProductEntity;
import com.suresell.mscoreapp.domain.port.out.MenuProductRepository;
import com.suresell.mscoreapp.domain.port.out.MenuCategoryRepository;
import com.suresell.mscoreapp.shared.exception.ReglaDeNegocioException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Ola 4: un producto precargado nace a $0 e inactivo, y el botón «Activar» lo
 * activaba igual. Se vendía a $0, justo lo que la precarga quería evitar.
 */
class SinPrecioNoSeActivaTest {

    private final MenuProductRepository productos = mock(MenuProductRepository.class);
    private final MenuCategoryRepository categorias = mock(MenuCategoryRepository.class);
    private final ManageMenuUseCase casoDeUso = new ManageMenuUseCase(
            categorias, productos, mock(MenuCategoryMapper.class), mock(MenuProductMapper.class));

    private MenuProductEntity producto(int precio) {
        MenuProductEntity e = new MenuProductEntity();
        e.setId("ferrepony-casco");
        e.setName("casco");
        e.setPrice(precio);
        e.setActive(false);
        return e;
    }

    @Test
    @DisplayName("🔴 activar un producto a $0 se rechaza con código, campo y texto; no se guarda")
    void activarSinPrecio() {
        when(productos.findById("ferrepony-casco")).thenReturn(Optional.of(producto(0)));

        assertThatThrownBy(() -> casoDeUso.updateProductStatus("ferrepony-casco", true))
                .isInstanceOf(ReglaDeNegocioException.class)
                .satisfies(e -> {
                    ReglaDeNegocioException r = (ReglaDeNegocioException) e;
                    org.assertj.core.api.Assertions.assertThat(r.codigo()).isEqualTo("SIN_PRECIO");
                    org.assertj.core.api.Assertions.assertThat(r.campo()).isEqualTo("price");
                })
                .hasMessageContaining("casco").hasMessageContaining("ponle precio");
        verify(productos, never()).save(any());
    }

    @Test
    @DisplayName("con precio se activa; y desactivar uno a $0 siempre se puede")
    void conPrecioSeActivaYDesactivarSiempre() {
        when(productos.findById("ferrepony-casco")).thenReturn(Optional.of(producto(12000)));
        when(productos.save(any())).thenAnswer(i -> i.getArgument(0));
        assertThatCode(() -> casoDeUso.updateProductStatus("ferrepony-casco", true)).doesNotThrowAnyException();

        when(productos.findById("ferrepony-casco")).thenReturn(Optional.of(producto(0)));
        assertThatCode(() -> casoDeUso.updateProductStatus("ferrepony-casco", false)).doesNotThrowAnyException();
    }
}
