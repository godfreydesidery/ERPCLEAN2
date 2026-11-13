package com.erp.modules.subscription.domain.enums;

import java.util.List;

/**
 * A sellable module and the permission codes that make it up (ADR-0069 Appendix B). Permission
 * groups are too coarse to be modules ({@code RESTAURANT.*} sits in {@code sales}), so the plan
 * classifies CODES. The first matching module wins; a code no module claims is {@link #CORE}.
 *
 * <p>A new permission code in a gated area must be classified here; {@code PlanModuleCoverageTest}
 * fails the build otherwise.
 */
public enum PlanModule {

    // The add-on comes first, so its prefix is claimed before any tier module could claim it.
    RESTAURANT("Restaurant", PlanCode.BASIC, AddOn.RESTAURANT, List.of("RESTAURANT."), List.of()),

    PURCHASING("Purchasing (LPO, requisitions, RFQ, returns, landed cost)", PlanCode.STANDARD, null,
            List.of("PURCHASE."), List.of("PURCHASE.RECEIVE.DIRECT", "PURCHASE.GOODS_RECEIPT.VIEW")),
    RECEIVABLES("Accounts receivable", PlanCode.STANDARD, null, List.of("AR."), List.of()),
    PAYABLES("Accounts payable", PlanCode.STANDARD, null, List.of("AP."), List.of()),
    CASH_BANK("Cash & bank", PlanCode.STANDARD, null,
            List.of("CASH.", "CHEQUE.", "PETTY_CASH."), List.of()),
    TAX_RETURNS("VAT & WHT returns", PlanCode.STANDARD, null, List.of("VAT.", "WHT."), List.of()),
    TRANSFERS("Inter-branch transfers", PlanCode.STANDARD, null, List.of("STOCK.TRANSFER."),
            List.of()),
    APPROVALS("Approvals", PlanCode.STANDARD, null, List.of("APPROVALS."), List.of()),

    GENERAL_LEDGER("General ledger & financial statements", PlanCode.PREMIUM, null,
            List.of("GL.", "REPORT.BS.VIEW", "REPORT.PL.VIEW", "REPORT.CASHFLOW.VIEW",
                    "REPORT.LEDGER.VIEW"), List.of()),
    FIXED_ASSETS("Fixed assets", PlanCode.PREMIUM, null, List.of("FA."), List.of()),
    COSTING("Costing", PlanCode.PREMIUM, null, List.of("COSTING."), List.of()),
    BUDGETING("Budgeting", PlanCode.PREMIUM, null, List.of("BUDGETING."), List.of()),
    FX("Foreign exchange", PlanCode.PREMIUM, null, List.of("FX.", "CURRENCY.MANAGE"), List.of()),
    BI("BI dashboards", PlanCode.PREMIUM, null, List.of("BI."), List.of()),
    ROUTES("Routes & van sales", PlanCode.PREMIUM, null,
            List.of("ROUTE.", "STOCK.VAN_RECON."), List.of()),
    CRM("CRM", PlanCode.PREMIUM, null, List.of("CRM."), List.of()),

    HR_PAYROLL("HR & payroll", PlanCode.ENTERPRISE, null, List.of("HR."), List.of()),
    MANUFACTURING("Manufacturing", PlanCode.ENTERPRISE, null,
            List.of("MANUFACTURING.", "WORKORDER.", "BOM."), List.of()),
    PROJECTS("Projects", PlanCode.ENTERPRISE, null, List.of("PROJECTS."), List.of()),

    /** POS, products, stock, sales, parties, fiscal receipts, IAM, documents, basic reports. */
    CORE("POS, products, stock, sales, customers & suppliers, reports", PlanCode.BASIC, null,
            List.of(), List.of());

    private final String displayName;
    private final PlanCode minPlan;
    private final AddOn addOn;
    /** A pattern ending in '.' matches by prefix; any other pattern matches exactly. */
    private final List<String> patterns;
    private final List<String> exceptions;

    PlanModule(String displayName, PlanCode minPlan, AddOn addOn, List<String> patterns,
               List<String> exceptions) {
        this.displayName = displayName;
        this.minPlan = minPlan;
        this.addOn = addOn;
        this.patterns = patterns;
        this.exceptions = exceptions;
    }

    public String displayName() {
        return displayName;
    }

    /** The lowest tier containing this module (ignored for an add-on module). */
    public PlanCode minPlan() {
        return minPlan;
    }

    /** The add-on that unlocks this module, or {@code null} for a tier module. */
    public AddOn addOn() {
        return addOn;
    }

    /** The module a permission code belongs to. */
    public static PlanModule of(String permissionCode) {
        if (permissionCode == null) {
            return CORE;
        }
        for (PlanModule m : values()) {
            if (m.claims(permissionCode)) {
                return m;
            }
        }
        return CORE;
    }

    private boolean claims(String code) {
        if (exceptions.contains(code)) {
            return false;
        }
        for (String p : patterns) {
            if (p.endsWith(".") ? code.startsWith(p) : code.equals(p)) {
                return true;
            }
        }
        return false;
    }
}
