package com.erp.modules.sales.service;

import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.sales.domain.dto.ModifierGroupDto;
import com.erp.modules.sales.domain.dto.ModifierLinkDto;
import com.erp.modules.sales.domain.dto.ModifierOptionDto;
import com.erp.modules.sales.domain.dto.ProductRefDto;
import com.erp.modules.sales.domain.dto.SaveModifierGroupRequest;
import com.erp.modules.sales.domain.entity.ModifierGroup;
import com.erp.modules.sales.domain.entity.ModifierOption;
import com.erp.modules.sales.domain.entity.ProductModifierGroup;
import com.erp.modules.sales.repository.ModifierGroupRepository;
import com.erp.modules.sales.repository.ModifierOptionRepository;
import com.erp.modules.sales.repository.ProductModifierGroupRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.MasterStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class RestaurantMenuServiceImpl implements RestaurantMenuService {

    private final ModifierGroupRepository groups;
    private final ModifierOptionRepository options;
    private final ProductModifierGroupRepository links;
    private final ProductRepository products;
    private final RestaurantScope scope;
    private final AuditService audit;

    public RestaurantMenuServiceImpl(ModifierGroupRepository groups,
                                     ModifierOptionRepository options,
                                     ProductModifierGroupRepository links,
                                     ProductRepository products,
                                     RestaurantScope scope,
                                     AuditService audit) {
        this.groups   = groups;
        this.options  = options;
        this.links    = links;
        this.products = products;
        this.scope    = scope;
        this.audit    = audit;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ModifierGroupDto> listGroups() {
        Long companyId = scope.companyId();
        return toDtos(companyId, groups.findByCompanyIdOrderBySortOrderAscNameAsc(companyId));
    }

    @Override
    public ModifierGroupDto createGroup(SaveModifierGroupRequest req) {
        Long companyId = scope.companyId();
        String name = req.name().trim();
        if (groups.existsByCompanyIdAndNameIgnoreCase(companyId, name)) {
            throw new ConflictException("There is already a choice called \"" + name + "\".");
        }
        var group = groups.save(new ModifierGroup(companyId, name, scope.actorId()));
        apply(group, req);
        audit.record(AuditEvent.of(AuditActions.MODIFIER_GROUP_CREATE, "modifier_groups",
                group.getId(), group.getUid()).detail(Map.of("name", name)));
        return toDtos(companyId, List.of(group)).get(0);
    }

    @Override
    public ModifierGroupDto updateGroup(String uid, SaveModifierGroupRequest req) {
        Long companyId = scope.companyId();
        var group = groups.findByCompanyIdAndUid(companyId, uid)
                .orElseThrow(() -> NotFoundException.of("ModifierGroup", uid));
        String name = req.name().trim();
        if (groups.existsByCompanyIdAndNameIgnoreCaseAndIdNot(companyId, name, group.getId())) {
            throw new ConflictException("There is already a choice called \"" + name + "\".");
        }
        group.setName(name);
        if (req.status() != null) {
            group.setStatus(req.status());
        }
        group.setUpdatedAt(Instant.now());
        group.setUpdatedBy(scope.actorId());
        apply(group, req);
        audit.record(AuditEvent.of(AuditActions.MODIFIER_GROUP_UPDATE, "modifier_groups",
                group.getId(), group.getUid())
                .detail(Map.of("name", name, "status", group.getStatus().name())));
        return toDtos(companyId, List.of(group)).get(0);
    }

    /** Writes the rule, the options and the item list of [group] from [req]. */
    private void apply(ModifierGroup group, SaveModifierGroupRequest req) {
        Long companyId = group.getCompanyId();
        short min = req.minSelect() == null ? 0 : req.minSelect();
        Short max = req.maxSelect();
        if (max != null && max < Math.max(min, 1)) {
            throw new IllegalArgumentException(
                    "The most a guest may choose cannot be less than the fewest they must choose.");
        }
        group.setMinSelect(min);
        group.setMaxSelect(max);
        if (req.sortOrder() != null) {
            group.setSortOrder(req.sortOrder());
        }

        // ---- options: update by uid, add new, deactivate the rest ----
        Map<String, ModifierOption> existing = options
                .findByCompanyIdAndModifierGroupId(companyId, group.getId()).stream()
                .collect(Collectors.toMap(ModifierOption::getUid, Function.identity()));
        Set<String> labels = new HashSet<>();
        Set<String> kept = new HashSet<>();
        Long actor = scope.actorId();
        Instant now = Instant.now();
        int order = 0;
        for (var o : req.options()) {
            String label = o.label().trim();
            if (!labels.add(label.toLowerCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException("\"" + label + "\" is listed twice in this choice.");
            }
            Long productId = null;
            if (o.productUid() != null && !o.productUid().isBlank()) {
                Product p = products.findByCompanyIdAndUid(companyId, o.productUid().trim())
                        .orElseThrow(() -> new NotFoundException(
                                "The item priced for \"" + label + "\" was not found."));
                if (!p.isSellable() || p.getStatus() != MasterStatus.ACTIVE) {
                    throw new IllegalArgumentException(p.getName() + " is not for sale.");
                }
                productId = p.getId();
            }
            // A row without a uid that names an existing option (say, one removed earlier and now
            // added back) reuses it: labels are unique per group, deactivated ones included.
            ModifierOption opt = o.uid() != null
                    ? existing.get(o.uid())
                    : existing.values().stream()
                            .filter(x -> x.getLabel().equalsIgnoreCase(label)
                                    && !kept.contains(x.getUid()))
                            .findFirst().orElse(null);
            if (opt == null) {
                opt = new ModifierOption(companyId, group.getId(), label, actor);
            } else {
                opt.setLabel(label);
                opt.setUpdatedAt(now);
                opt.setUpdatedBy(actor);
                kept.add(opt.getUid());
            }
            opt.setProductId(productId);
            opt.setSortOrder(o.sortOrder() != null ? o.sortOrder() : order);
            opt.setStatus(MasterStatus.ACTIVE);
            options.save(opt);
            order++;
        }
        for (var opt : existing.values()) {
            if (!kept.contains(opt.getUid()) && opt.getStatus() == MasterStatus.ACTIVE) {
                opt.setStatus(MasterStatus.INACTIVE);
                opt.setUpdatedAt(now);
                opt.setUpdatedBy(actor);
            }
        }
        // Rename-swaps ("A"→"B", "B"→"A") would trip the unique label index row by row; flush once
        // all labels are final rather than mid-loop.
        options.flush();

        // ---- items: replace the list ----
        Set<Long> productIds = new LinkedHashSet<>();
        for (String productUid : req.productUids()) {
            Product p = products.findByCompanyIdAndUid(companyId, productUid)
                    .orElseThrow(() -> NotFoundException.of("Product", productUid));
            productIds.add(p.getId());
        }
        links.deleteByCompanyIdAndModifierGroupId(companyId, group.getId());
        for (Long productId : productIds) {
            links.save(new ProductModifierGroup(companyId, productId, group.getId(), actor));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<ModifierLinkDto> listLinks() {
        Long companyId = scope.companyId();
        Map<Long, ModifierGroup> active = groups.findByCompanyIdOrderBySortOrderAscNameAsc(companyId)
                .stream().filter(g -> g.getStatus() == MasterStatus.ACTIVE)
                .collect(Collectors.toMap(ModifierGroup::getId, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
        Map<Long, List<ModifierGroup>> byProduct = new LinkedHashMap<>();
        for (var l : links.findByCompanyId(companyId)) {
            var g = active.get(l.getModifierGroupId());
            if (g != null) {
                byProduct.computeIfAbsent(l.getProductId(), k -> new ArrayList<>()).add(g);
            }
        }
        Map<Long, String> productUids = productUids(companyId, byProduct.keySet());
        List<ModifierLinkDto> out = new ArrayList<>();
        for (var e : byProduct.entrySet()) {
            var ordered = e.getValue().stream()
                    .sorted(java.util.Comparator.comparing(ModifierGroup::getSortOrder)
                            .thenComparing(ModifierGroup::getName))
                    .map(ModifierGroup::getUid).toList();
            out.add(new ModifierLinkDto(e.getKey(), productUids.get(e.getKey()), ordered));
        }
        return out;
    }

    // -------------------------------------------------------------------------

    private List<ModifierGroupDto> toDtos(Long companyId, List<ModifierGroup> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        var ids = list.stream().map(ModifierGroup::getId).toList();
        Map<Long, List<ModifierOption>> optionsByGroup = options
                .findByCompanyIdAndModifierGroupIdInOrderBySortOrderAscLabelAsc(companyId, ids).stream()
                .collect(Collectors.groupingBy(ModifierOption::getModifierGroupId));
        Map<Long, List<Long>> productsByGroup = new HashMap<>();
        for (var l : links.findByCompanyId(companyId)) {
            productsByGroup.computeIfAbsent(l.getModifierGroupId(), k -> new ArrayList<>())
                    .add(l.getProductId());
        }
        Set<Long> productIds = new HashSet<>();
        optionsByGroup.values().forEach(os -> os.forEach(o -> {
            if (o.getProductId() != null) {
                productIds.add(o.getProductId());
            }
        }));
        ids.forEach(id -> productIds.addAll(productsByGroup.getOrDefault(id, List.of())));
        Map<Long, Product> productById = new HashMap<>();
        for (Long id : productIds) {
            products.findByCompanyIdAndId(companyId, id).ifPresent(p -> productById.put(id, p));
        }
        return list.stream().map(g -> new ModifierGroupDto(g.getUid(), g.getName(),
                g.getMinSelect(), g.getMaxSelect(), g.getSortOrder(), g.getStatus(),
                optionsByGroup.getOrDefault(g.getId(), List.of()).stream().map(o -> {
                    Product p = o.getProductId() == null ? null : productById.get(o.getProductId());
                    return new ModifierOptionDto(o.getUid(), o.getLabel(), o.getProductId(),
                            p == null ? null : p.getUid(), p == null ? null : p.getName(),
                            o.getSortOrder(), o.getStatus());
                }).toList(),
                productsByGroup.getOrDefault(g.getId(), List.of()).stream()
                        .map(productById::get).filter(java.util.Objects::nonNull)
                        .map(p -> new ProductRefDto(p.getUid(), p.getName())).toList()))
                .toList();
    }

    private Map<Long, String> productUids(Long companyId, Set<Long> ids) {
        Map<Long, String> out = new HashMap<>();
        for (Long id : ids) {
            products.findByCompanyIdAndId(companyId, id).ifPresent(p -> out.put(id, p.getUid()));
        }
        return out;
    }
}
