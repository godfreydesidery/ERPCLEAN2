package com.erp.api;

import com.erp.modules.fixedassets.domain.dto.DepreciationScheduleLineDto;
import com.erp.modules.fixedassets.domain.dto.FixedAssetDto;
import com.erp.modules.fixedassets.domain.dto.FixedAssetRegisterDto;
import com.erp.modules.fixedassets.domain.dto.FixedAssetRegisterRowDto;
import com.erp.modules.fixedassets.domain.dto.FixedAssetRegisterTotalDto;
import com.erp.modules.fixedassets.domain.enums.FixedAssetStatus;
import com.erp.modules.fixedassets.service.DepreciationScheduleService;
import com.erp.modules.fixedassets.service.FixedAssetRegisterQuery;
import com.erp.modules.fixedassets.service.FixedAssetService;
import com.erp.modules.reporting.domain.enums.ExportFormat;
import com.erp.modules.reporting.export.CompanyLetterheadLines;
import com.erp.modules.reporting.export.ExportResult;
import com.erp.modules.reporting.export.TabularExporter;
import com.erp.modules.reporting.export.TabularRenderModel;
import com.erp.modules.reporting.export.TabularRenderModel.Align;
import com.erp.modules.reporting.export.TabularRenderModel.Column;
import com.erp.modules.reporting.service.ReportCompanyHeaderQuery;
import com.erp.platform.security.RequestContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fixed-asset reports: the Asset Register as at a date (FR-FA-17) and the per-asset depreciation
 * schedule as a file (FR-FA-18). Gated {@code FA.VIEW} like every fixed-asset read; exports add
 * {@code REPORT.EXPORT}. Company from the request context, never a parameter.
 */
@RestController
@RequestMapping("/api/v1/fixed-assets")
public class FixedAssetReportController {

    private final FixedAssetRegisterQuery     registerQuery;
    private final FixedAssetService           assetService;
    private final DepreciationScheduleService scheduleService;
    private final TabularExporter             exporter;
    private final ReportCompanyHeaderQuery    companyHeaderQuery;

    public FixedAssetReportController(FixedAssetRegisterQuery registerQuery,
                                      FixedAssetService assetService,
                                      DepreciationScheduleService scheduleService,
                                      TabularExporter exporter,
                                      ReportCompanyHeaderQuery companyHeaderQuery) {
        this.registerQuery      = registerQuery;
        this.assetService       = assetService;
        this.scheduleService    = scheduleService;
        this.exporter           = exporter;
        this.companyHeaderQuery = companyHeaderQuery;
    }

    @GetMapping("/register")
    @PreAuthorize("@perm.has('FA.VIEW')")
    public FixedAssetRegisterDto register(
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate asOf,
            @RequestParam(required = false) String categoryUid,
            @RequestParam(required = false) FixedAssetStatus status,
            @RequestParam(required = false) String branchUid,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) String costCentreUid) {
        return registerQuery.register(RequestContext.get().companyId(), asOf, categoryUid, status,
                branchUid, location, costCentreUid);
    }

    @GetMapping("/register/export")
    @PreAuthorize("@perm.has('FA.VIEW') and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> exportRegister(
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate asOf,
            @RequestParam(required = false) String categoryUid,
            @RequestParam(required = false) FixedAssetStatus status,
            @RequestParam(required = false) String branchUid,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) String costCentreUid,
            @RequestParam(defaultValue = "PDF") ExportFormat format) {
        Long companyId = RequestContext.get().companyId();
        FixedAssetRegisterDto dto = registerQuery.register(companyId, asOf, categoryUid, status,
                branchUid, location, costCentreUid);
        return download(exporter.export(flattenRegister(dto, companyId), format));
    }

    /** FR-FA-18: the asset's depreciation schedule (planned + posted, every version) as a file. */
    @GetMapping("/uid/{uid}/schedule/export")
    @PreAuthorize("@perm.scoped(#uid,'fixedasset','FA.VIEW') and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> exportSchedule(
            @PathVariable String uid,
            @RequestParam(defaultValue = "PDF") ExportFormat format) {
        FixedAssetDto asset = assetService.getByUid(uid);
        List<DepreciationScheduleLineDto> lines = scheduleService.listByAsset(uid);
        return download(exporter.export(flattenSchedule(asset, lines), format));
    }

    // -------------------------------------------------------------------------

    private TabularRenderModel flattenRegister(FixedAssetRegisterDto dto, Long companyId) {
        List<String> headerLines = new ArrayList<>(
                CompanyLetterheadLines.of(companyHeaderQuery.forCompany(companyId)));
        headerLines.add("As at " + dto.asOf() + " - Currency: " + dto.currency());
        List<String> filters = new ArrayList<>();
        if (dto.categoryName() != null) filters.add("Category: " + dto.categoryName());
        if (dto.branchName() != null) filters.add("Branch: " + dto.branchName());
        if (dto.status() != null) filters.add("Status: " + dto.status());
        if (dto.location() != null) filters.add("Location contains: " + dto.location());
        if (dto.costCentreName() != null) filters.add("Cost centre: " + dto.costCentreName());
        if (!filters.isEmpty()) headerLines.add(String.join(" - ", filters));

        List<Column> columns = List.of(
                new Column("Asset No", Align.LEFT),
                new Column("Name", Align.LEFT),
                new Column("Category", Align.LEFT),
                new Column("Branch", Align.LEFT),
                new Column("Location", Align.LEFT),
                new Column("Acquired", Align.LEFT),
                new Column("Cost", Align.RIGHT),
                new Column("Accum. Dep.", Align.RIGHT),
                new Column("NBV", Align.RIGHT),
                new Column("Status", Align.LEFT));

        List<List<String>> rows = new ArrayList<>();
        String currentCategory = null;
        for (FixedAssetRegisterRowDto r : dto.rows()) {
            if (currentCategory != null && !Objects.equals(currentCategory, r.categoryCode())) {
                addSubtotal(rows, dto, currentCategory);
            }
            currentCategory = r.categoryCode();
            rows.add(List.of(
                    nz(r.assetNumber()), nz(r.name()), nz(r.categoryName()), nz(r.branchName()),
                    nz(r.location()), String.valueOf(r.acquisitionDate()),
                    amt(r.cost()), amt(r.accumulatedDepreciation()),
                    r.nbv() != null ? amt(r.nbv()) : "-",
                    r.status().name() + (r.inTotals() ? "" : " *")));
        }
        if (currentCategory != null) {
            addSubtotal(rows, dto, currentCategory);
        }

        FixedAssetRegisterTotalDto g = dto.grandTotal();
        List<String> totals = List.of("", "GRAND TOTAL (" + g.assetCount() + " in service)", "", "",
                "", "", amt(g.cost()), amt(g.accumulatedDepreciation()), amt(g.nbv()), "");

        List<String> footer = new ArrayList<>();
        footer.add("Totals cover assets in service at the date. NBV = cost - accumulated depreciation.");
        if (dto.rowsNotInTotals() > 0) {
            footer.add("* " + dto.rowsNotInTotals() + " listed asset(s) were draft, disposed or written "
                    + "off at the date: they carry no book value and are left out of the totals.");
        }
        return new TabularRenderModel("Fixed Asset Register", headerLines, dto.generatedAt(),
                columns, rows, totals, footer);
    }

    private void addSubtotal(List<List<String>> rows, FixedAssetRegisterDto dto, String categoryCode) {
        dto.categoryTotals().stream()
                .filter(t -> Objects.equals(t.categoryCode(), categoryCode))
                .findFirst()
                .ifPresent(t -> rows.add(List.of("", "Subtotal - " + nz(t.categoryName())
                                + " (" + t.assetCount() + ")", "", "", "", "",
                        amt(t.cost()), amt(t.accumulatedDepreciation()), amt(t.nbv()), "")));
    }

    private TabularRenderModel flattenSchedule(FixedAssetDto asset, List<DepreciationScheduleLineDto> lines) {
        List<String> headerLines = new ArrayList<>(
                CompanyLetterheadLines.of(companyHeaderQuery.forCompany(asset.companyId())));
        headerLines.add("Asset " + asset.assetNumber() + " - " + asset.name());
        headerLines.add("Method: " + asset.depreciationMethod() + " - Life: " + asset.lifePeriods()
                + " periods - Cost: " + amt(asset.carryingCost()) + " - Salvage: "
                + amt(asset.salvageValue()));

        List<Column> columns = List.of(
                new Column("Version", Align.RIGHT),
                new Column("#", Align.RIGHT),
                new Column("Period date", Align.LEFT),
                new Column("Planned charge", Align.RIGHT),
                new Column("Accum. after", Align.RIGHT),
                new Column("NBV after", Align.RIGHT),
                new Column("Posted", Align.LEFT));

        List<List<String>> rows = new ArrayList<>(lines.size());
        BigDecimal posted = BigDecimal.ZERO;
        for (DepreciationScheduleLineDto l : lines) {
            if (l.posted()) {
                posted = posted.add(l.plannedCharge());
            }
            rows.add(List.of(
                    String.valueOf(l.scheduleVersion()), String.valueOf(l.periodSeq()),
                    String.valueOf(l.periodDate()), amt(l.plannedCharge()),
                    amt(l.accumulatedAfter()), amt(l.nbvAfter()), l.posted() ? "Posted" : "Pending"));
        }
        List<String> totals = List.of("", "", "Posted to date", amt(posted), "", "", "");
        return new TabularRenderModel("Depreciation Schedule " + asset.assetNumber(), headerLines,
                Instant.now().toString(), columns, rows, totals,
                List.of("Accumulated depreciation on the asset: " + amt(asset.accumulatedDepreciation())
                        + " - NBV: " + amt(asset.nbv())));
    }

    private static String nz(String s) {
        return s != null ? s : "";
    }

    private static String amt(BigDecimal v) {
        return v != null ? String.format("%,.2f", v) : "";
    }

    private static ResponseEntity<byte[]> download(ExportResult result) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + result.filename() + "\"")
                .body(result.content());
    }
}
