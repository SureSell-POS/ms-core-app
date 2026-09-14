package com.suresell.mscoreapp.application.usecase;

import com.suresell.mscoreapp.application.dto.CreateMenuCategoryRequest;
import com.suresell.mscoreapp.application.dto.CreateMenuProductRequest;
import com.suresell.mscoreapp.application.dto.MenuCategoryDto;
import com.suresell.mscoreapp.application.dto.MenuProductDto;
import com.suresell.mscoreapp.domain.model.MenuCategoryEntity;
import com.suresell.mscoreapp.domain.model.MenuProductEntity;
import com.suresell.mscoreapp.domain.port.out.MenuCategoryRepository;
import com.suresell.mscoreapp.domain.port.out.MenuProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ManageMenuUseCase {

    private final MenuCategoryRepository categoryRepository;
    private final MenuProductRepository productRepository;
    private final MenuCategoryMapper categoryMapper;
    private final MenuProductMapper productMapper;

    // --- Categorías ---

    @Transactional(readOnly = true)
    public Page<MenuCategoryDto> getAllCategories(Pageable pageable) {
        return categoryRepository.findAll(pageable).map(categoryMapper::toDto);
    }

    @Transactional
    public MenuCategoryDto saveCategory(CreateMenuCategoryRequest request) {
        MenuCategoryEntity entity = new MenuCategoryEntity();
        entity.setId(request.getId());
        entity.setName(request.getName());
        return categoryMapper.toDto(categoryRepository.save(entity));
    }

    @Transactional
    public void deleteCategory(String id) {
        if (!productRepository.findByCategoryId(id, Pageable.ofSize(1)).isEmpty()) {
            throw new IllegalStateException("No se puede eliminar una categoría que tiene productos asociados");
        }
        categoryRepository.deleteById(id);
    }

    // --- Productos ---

    @Transactional(readOnly = true)
    public Page<MenuProductDto> getAllProducts(Pageable pageable) {
        return productRepository.findAll(pageable).map(productMapper::toDto);
    }

    @Transactional(readOnly = true)
    public Page<MenuProductDto> getProductsByCategory(String categoryId, Pageable pageable) {
        return productRepository.findByCategoryId(categoryId, pageable).map(productMapper::toDto);
    }

    @Transactional
    public MenuProductDto saveProduct(CreateMenuProductRequest request) {
        MenuCategoryEntity category = categoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new IllegalArgumentException("Categoría no encontrada: " + request.getCategoryId()));

        exigirPrecioParaActivar(request.getName(), request.getPrice(), Boolean.TRUE.equals(request.getActive()));

        MenuProductEntity entity = new MenuProductEntity();
        entity.setId(request.getId());
        entity.setName(request.getName());
        entity.setPrice(request.getPrice());
        entity.setActive(request.getActive());
        entity.setCategory(category);

        return productMapper.toDto(productRepository.save(entity));
    }

    /**
     * Un producto sin precio no se vende: se activa cuando lo tenga. Nació con
     * la precarga (ola 4): los precargados nacen a $0 e inactivos, y el botón
     * «Activar» los dejaba vender a $0. Vale también para el que se crea a mano.
     */
    static void exigirPrecioParaActivar(String nombre, Integer precio, boolean activar) {
        if (activar && (precio == null || precio <= 0)) {
            throw new com.suresell.mscoreapp.shared.exception.ReglaDeNegocioException(
                    "SIN_PRECIO", "price",
                    "«" + nombre + "» no tiene precio: ponle precio antes de activarlo, "
                    + "o se vendería a $0.");
        }
    }

    @Transactional
    public MenuProductDto updateProductStatus(String id, boolean active) {
        MenuProductEntity entity = productRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Producto no encontrado: " + id));
        exigirPrecioParaActivar(entity.getName(), entity.getPrice(), active);
        entity.setActive(active);
        return productMapper.toDto(productRepository.save(entity));
    }

    @Transactional
    public void deleteProduct(String id) {
        productRepository.deleteById(id);
    }
}
