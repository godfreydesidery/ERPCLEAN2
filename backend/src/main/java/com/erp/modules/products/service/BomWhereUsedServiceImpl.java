package com.erp.modules.products.service;

import com.erp.modules.products.domain.dto.WhereUsedRowDto;
import com.erp.modules.products.domain.dto.WhereUsedTreeDto;
import com.erp.modules.products.domain.entity.Bom;
import com.erp.modules.products.domain.entity.BomComponent;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.repository.BomComponentRepository;
import com.erp.modules.products.repository.BomRepository;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.repository.Lookups;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where-used (implosion) implementation (ADR-0026 D-6, FR-BOM-14/15).
 *
 * <p>Single-level is the guaranteed minimum (ix_bom_components_child serves this efficiently).
 * Full implosion walks up via BOM headers' parent products until no more parents are found,
 * bounded by maxDepth (OQ-BOM-04 best-effort).
 */
@Service
@Transactional(readOnly = true)
public class BomWhereUsedServiceImpl implements BomWhereUsedService {

    private final BomComponentRepository bomComponents;
    private final BomRepository boms;
    private final ProductRepository products;
    private final int maxDepth;

    public BomWhereUsedServiceImpl(BomComponentRepository bomComponents,
                                    BomRepository boms,
                                    ProductRepository products,
                                    @Value("${bom.max-explosion-depth:20}") int maxDepth) {
        this.bomComponents = bomComponents;
        this.boms = boms;
        this.products = products;
        this.maxDepth = maxDepth;
    }

    @Override
    public List<WhereUsedRowDto> whereUsed(String componentProductUid, Long companyId) {
        Product comp = Lookups.orNotFound(products.findByUid(componentProductUid), "Product", componentProductUid);

        List<BomComponent> lines = bomComponents.findByComponentProductIdAndCompanyId(
                comp.getId(), companyId);
        if (lines.isEmpty()) {
            return List.of();
        }

        // Batch-load the BOM headers
        List<Long> bomIds = lines.stream().map(BomComponent::getBomId).distinct().toList();
        Map<Long, Bom> bomById = boms.findAllById(bomIds).stream()
                .collect(Collectors.toMap(Bom::getId, b -> b));

        // Batch-load the parent products
        List<Long> parentProductIds = bomById.values().stream()
                .map(Bom::getParentProductId).distinct().toList();
        Map<Long, Product> productById = products.findAllById(parentProductIds).stream()
                .collect(Collectors.toMap(Product::getId, p -> p));

        List<WhereUsedRowDto> result = new ArrayList<>();
        for (BomComponent line : lines) {
            Bom bom = bomById.get(line.getBomId());
            if (bom == null) continue;
            Product parent = productById.get(bom.getParentProductId());
            if (parent == null) continue;
            result.add(new WhereUsedRowDto(
                    bom.getUid(),
                    bom.getVersionNo(),
                    bom.getStatus(),
                    parent.getUid(),
                    parent.getCode(),
                    parent.getName(),
                    line.getQtyPer(),
                    line.getSourcing()
            ));
        }
        return result;
    }

    @Override
    public WhereUsedTreeDto whereUsedTree(String componentProductUid, Long companyId) {
        Product comp = Lookups.orNotFound(products.findByUid(componentProductUid), "Product", componentProductUid);

        Set<Long> visited = new HashSet<>();
        List<WhereUsedTreeDto.WhereUsedNodeDto> parentNodes =
                buildParentNodes(comp.getId(), companyId, visited, 0);

        return new WhereUsedTreeDto(comp.getUid(), comp.getName(), parentNodes);
    }

    private List<WhereUsedTreeDto.WhereUsedNodeDto> buildParentNodes(Long componentProductId,
                                                                       Long companyId,
                                                                       Set<Long> visited,
                                                                       int depth) {
        if (depth >= maxDepth) {
            return List.of(); // depth guard (BR-BOM-11)
        }

        List<BomComponent> lines = bomComponents.findByComponentProductIdAndCompanyId(
                componentProductId, companyId);
        if (lines.isEmpty()) {
            return List.of();
        }

        List<Long> bomIds = lines.stream().map(BomComponent::getBomId).distinct().toList();
        Map<Long, Bom> bomById = boms.findAllById(bomIds).stream()
                .collect(Collectors.toMap(Bom::getId, b -> b));

        List<Long> parentProductIds = bomById.values().stream()
                .map(Bom::getParentProductId).distinct().toList();
        Map<Long, Product> productById = products.findAllById(parentProductIds).stream()
                .collect(Collectors.toMap(Product::getId, p -> p));

        List<WhereUsedTreeDto.WhereUsedNodeDto> nodes = new ArrayList<>();
        for (BomComponent line : lines) {
            Bom bom = bomById.get(line.getBomId());
            if (bom == null) continue;
            Product parent = productById.get(bom.getParentProductId());
            if (parent == null) continue;

            // Prevent cycles in the implosion traversal
            if (visited.contains(parent.getId())) continue;
            visited.add(parent.getId());

            List<WhereUsedTreeDto.WhereUsedNodeDto> grandParents =
                    buildParentNodes(parent.getId(), companyId, visited, depth + 1);

            nodes.add(new WhereUsedTreeDto.WhereUsedNodeDto(
                    bom.getUid(),
                    bom.getVersionNo(),
                    parent.getUid(),
                    parent.getCode(),
                    parent.getName(),
                    line.getQtyPer(),
                    grandParents
            ));
        }
        return nodes;
    }
}
