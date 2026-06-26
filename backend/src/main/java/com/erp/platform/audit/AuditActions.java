package com.erp.platform.audit;

/**
 * Catalogue of {@code action} string constants for {@code audit_log.action} (DATA-MODEL §1.11,
 * ADR-0004 D-6). Use these constants at every emit call site to prevent typos and ease
 * grep-ability. Not all actions are wired to emit calls yet — they are defined here so the full
 * catalogue is visible and searchable from day one.
 */
public final class AuditActions {

    private AuditActions() {
    }

    // -- User lifecycle -------------------------------------------------------
    public static final String USER_CREATE       = "USER.CREATE";
    public static final String USER_DISABLE      = "USER.DISABLE";
    public static final String USER_ENABLE       = "USER.ENABLE";
    public static final String USER_UNLOCK       = "USER.UNLOCK";
    public static final String USER_PASSWORD_SET = "USER.PASSWORD_SET";
    public static final String USER_UPDATE       = "USER.UPDATE";

    // -- Role management ------------------------------------------------------
    public static final String ROLE_GRANT           = "ROLE.GRANT";
    public static final String ROLE_REVOKE          = "ROLE.REVOKE";
    public static final String ROLE_CREATE          = "ROLE.CREATE";
    public static final String ROLE_PERMISSIONS_SET = "ROLE.PERMISSIONS_SET";

    // -- Branch assignment ----------------------------------------------------
    public static final String BRANCH_ASSIGN      = "BRANCH.ASSIGN";
    public static final String BRANCH_UNASSIGN    = "BRANCH.UNASSIGN";
    public static final String BRANCH_SET_DEFAULT = "BRANCH.SET_DEFAULT";

    // -- Company membership (V77) ---------------------------------------------
    public static final String USER_COMPANY_ASSIGN = "USER.COMPANY.ASSIGN";
    public static final String USER_COMPANY_REMOVE = "USER.COMPANY.REMOVE";

    // -- Authentication / lockout ---------------------------------------------
    public static final String LOGIN_SUCCESS = "LOGIN.SUCCESS";
    public static final String LOGIN_FAIL    = "LOGIN.FAIL";
    public static final String ACCOUNT_LOCKED = "ACCOUNT.LOCKED";

    // -- Root bypass (ADR-0004 D-9) -------------------------------------------
    public static final String ROOT_BYPASS = "ROOT.BYPASS";

    // -- Customer lifecycle (ADR-0006 D-12) -----------------------------------
    public static final String CUSTOMER_CREATE        = "CUSTOMER.CREATE";
    public static final String CUSTOMER_UPDATE        = "CUSTOMER.UPDATE";
    public static final String CUSTOMER_ARCHIVE       = "CUSTOMER.ARCHIVE";
    public static final String CUSTOMER_RESTORE       = "CUSTOMER.RESTORE";
    public static final String CUSTOMER_BRANCH_ADD    = "CUSTOMER.BRANCH.ADD";
    public static final String CUSTOMER_BRANCH_REMOVE = "CUSTOMER.BRANCH.REMOVE";

    // -- Supplier lifecycle ---------------------------------------------------
    public static final String SUPPLIER_CREATE        = "SUPPLIER.CREATE";
    public static final String SUPPLIER_UPDATE        = "SUPPLIER.UPDATE";
    public static final String SUPPLIER_ARCHIVE       = "SUPPLIER.ARCHIVE";
    public static final String SUPPLIER_RESTORE       = "SUPPLIER.RESTORE";
    public static final String SUPPLIER_BRANCH_ADD    = "SUPPLIER.BRANCH.ADD";
    public static final String SUPPLIER_BRANCH_REMOVE = "SUPPLIER.BRANCH.REMOVE";

    // -- Agent lifecycle ------------------------------------------------------
    public static final String AGENT_CREATE        = "AGENT.CREATE";
    public static final String AGENT_UPDATE        = "AGENT.UPDATE";
    public static final String AGENT_ARCHIVE       = "AGENT.ARCHIVE";
    public static final String AGENT_RESTORE       = "AGENT.RESTORE";
    public static final String AGENT_BRANCH_ADD    = "AGENT.BRANCH.ADD";
    public static final String AGENT_BRANCH_REMOVE = "AGENT.BRANCH.REMOVE";

    // -- OtherParty lifecycle -------------------------------------------------
    public static final String OTHERPARTY_CREATE        = "OTHERPARTY.CREATE";
    public static final String OTHERPARTY_UPDATE        = "OTHERPARTY.UPDATE";
    public static final String OTHERPARTY_ARCHIVE       = "OTHERPARTY.ARCHIVE";
    public static final String OTHERPARTY_RESTORE       = "OTHERPARTY.RESTORE";
    public static final String OTHERPARTY_BRANCH_ADD    = "OTHERPARTY.BRANCH.ADD";
    public static final String OTHERPARTY_BRANCH_REMOVE = "OTHERPARTY.BRANCH.REMOVE";

    // -- Product lifecycle (ADR-0007 D-13) ------------------------------------
    public static final String PRODUCT_CREATE           = "PRODUCT.CREATE";
    public static final String PRODUCT_UPDATE           = "PRODUCT.UPDATE";
    public static final String PRODUCT_ARCHIVE          = "PRODUCT.ARCHIVE";
    public static final String PRODUCT_RESTORE          = "PRODUCT.RESTORE";
    public static final String PRODUCT_BRANCH_ADD       = "PRODUCT.BRANCH.ADD";
    public static final String PRODUCT_BRANCH_REMOVE    = "PRODUCT.BRANCH.REMOVE";
    public static final String PRODUCT_BARCODE_ADD      = "PRODUCT.BARCODE.ADD";
    public static final String PRODUCT_BARCODE_REMOVE   = "PRODUCT.BARCODE.REMOVE";
    public static final String PRODUCT_BARCODE_SETPRIMARY = "PRODUCT.BARCODE.SETPRIMARY";
    public static final String PRODUCT_PRICE_SET        = "PRODUCT.PRICE.SET";
    public static final String PRODUCT_PRICE_REMOVE     = "PRODUCT.PRICE.REMOVE";
    public static final String PRODUCT_COMPONENT_ADD    = "PRODUCT.COMPONENT.ADD";
    public static final String PRODUCT_COMPONENT_REMOVE = "PRODUCT.COMPONENT.REMOVE";

    // -- PriceList lifecycle --------------------------------------------------
    public static final String PRICELIST_CREATE         = "PRICELIST.CREATE";
    public static final String PRICELIST_UPDATE         = "PRICELIST.UPDATE";
    public static final String PRICELIST_ARCHIVE        = "PRICELIST.ARCHIVE";
    public static final String PRICELIST_RESTORE        = "PRICELIST.RESTORE";

    // -- UnitOfMeasure lifecycle (brief §AuditActions) ------------------------
    public static final String UOM_CREATE               = "UOM.CREATE";
    public static final String UOM_UPDATE               = "UOM.UPDATE";
    public static final String UOM_ARCHIVE              = "UOM.ARCHIVE";
    public static final String UOM_RESTORE              = "UOM.RESTORE";

    // -- Sales Invoice lifecycle (ADR-0008 D-13; target_type = plural table names) ------------
    public static final String SALES_INVOICE_CREATE          = "SALES.INVOICE.CREATE";
    public static final String SALES_INVOICE_UPDATE          = "SALES.INVOICE.UPDATE";
    public static final String SALES_INVOICE_LINE_ADD        = "SALES.INVOICE.LINE.ADD";
    public static final String SALES_INVOICE_LINE_UPDATE     = "SALES.INVOICE.LINE.UPDATE";
    public static final String SALES_INVOICE_LINE_REMOVE     = "SALES.INVOICE.LINE.REMOVE";
    public static final String SALES_INVOICE_LINE_OVERRIDE   = "SALES.INVOICE.LINE.OVERRIDE";
    public static final String SALES_INVOICE_FINALISE        = "SALES.INVOICE.FINALISE";
    public static final String SALES_INVOICE_PAYMENT_ADD     = "SALES.INVOICE.PAYMENT.ADD";
    public static final String SALES_INVOICE_PAYMENT_REMOVE  = "SALES.INVOICE.PAYMENT.REMOVE";
    public static final String SALES_INVOICE_VOID            = "SALES.INVOICE.VOID";
    public static final String TAXRATE_CREATE                = "TAXRATE.CREATE";
    public static final String TAXRATE_UPDATE                = "TAXRATE.UPDATE";

    // -- Stock module (ADR-0010 D-12; target_type = plural table names) -------
    public static final String STOCK_ADJUST      = "STOCK.ADJUST";
    public static final String STOCK_OPENING     = "STOCK.OPENING";
    public static final String STOCK_REORDER_SET = "STOCK.REORDER.SET";

    // -- Purchases module (ADR-0011 D-13; target_type = plural table names) --
    public static final String PURCHASE_ORDER_CREATE      = "PURCHASE.ORDER.CREATE";
    public static final String PURCHASE_ORDER_UPDATE      = "PURCHASE.ORDER.UPDATE";
    public static final String PURCHASE_ORDER_LINE_ADD    = "PURCHASE.ORDER.LINE.ADD";
    public static final String PURCHASE_ORDER_LINE_UPDATE = "PURCHASE.ORDER.LINE.UPDATE";
    public static final String PURCHASE_ORDER_LINE_REMOVE = "PURCHASE.ORDER.LINE.REMOVE";
    public static final String PURCHASE_ORDER_PLACE       = "PURCHASE.ORDER.PLACE";
    public static final String PURCHASE_ORDER_CLOSE       = "PURCHASE.ORDER.CLOSE";
    public static final String PURCHASE_ORDER_VOID        = "PURCHASE.ORDER.VOID";
    public static final String PURCHASE_GR_CREATE         = "PURCHASE.GOODS_RECEIPT.CREATE";
    public static final String PURCHASE_GR_RECEIVE        = "PURCHASE.GOODS_RECEIPT.RECEIVE";
    public static final String PURCHASE_GR_VOID           = "PURCHASE.GOODS_RECEIPT.VOID";

    // -- GL module (ADR-0013 D-14; target_type = plural table names) ---------
    public static final String GL_ACCOUNT_CREATE     = "GL.ACCOUNT.CREATE";
    public static final String GL_ACCOUNT_UPDATE     = "GL.ACCOUNT.UPDATE";
    public static final String GL_ACCOUNT_DEACTIVATE = "GL.ACCOUNT.DEACTIVATE";
    public static final String GL_JOURNAL_POST       = "GL.JOURNAL.POST";
    public static final String GL_PERIOD_OPEN        = "GL.PERIOD.OPEN";
    public static final String GL_PERIOD_CLOSE       = "GL.PERIOD.CLOSE";
    public static final String GL_CONFIG_SET         = "GL.CONFIG.SET";

    // -- AR module (ADR-0014 D-13; target_type = plural table names) ---------
    public static final String AR_OPENITEM_CREATE  = "AR.OPENITEM.CREATE";
    public static final String AR_RECEIPT_RECORD   = "AR.RECEIPT.RECORD";
    public static final String AR_RECEIPT_ALLOCATE = "AR.RECEIPT.ALLOCATE";
    public static final String AR_WRITEOFF         = "AR.WRITEOFF";
    public static final String AR_CREDITNOTE_RAISE = "AR.CREDITNOTE.RAISE";
    public static final String AR_OPENING_SET      = "AR.OPENING.SET";
    public static final String SALES_CREDIT_OVERRIDE = "SALES.CREDIT.OVERRIDE";

    // -- Routes module (ADR-0012 D-12; target_type = plural table names) ----
    public static final String ROUTE_CREATE            = "ROUTE.CREATE";
    public static final String ROUTE_UPDATE            = "ROUTE.UPDATE";
    public static final String ROUTE_ARCHIVE           = "ROUTE.ARCHIVE";
    public static final String ROUTE_RESTORE           = "ROUTE.RESTORE";
    public static final String ROUTE_CUSTOMER_ADD      = "ROUTE.CUSTOMER.ADD";
    public static final String ROUTE_CUSTOMER_REMOVE   = "ROUTE.CUSTOMER.REMOVE";
    public static final String ROUTE_AGENT_ADD         = "ROUTE.AGENT.ADD";
    public static final String ROUTE_AGENT_REMOVE      = "ROUTE.AGENT.REMOVE";
    public static final String ROUTE_AGENT_SETPRIMARY  = "ROUTE.AGENT.SETPRIMARY";
    public static final String ROUTE_AGENT_CLEARPRIMARY = "ROUTE.AGENT.CLEARPRIMARY";
    public static final String ROUTE_BRANCH_ADD        = "ROUTE.BRANCH.ADD";
    public static final String ROUTE_BRANCH_REMOVE     = "ROUTE.BRANCH.REMOVE";

    // ---- AP (ADR-0015 D-13) ----
    public static final String AP_BILL_ENTER       = "AP.BILL.ENTER";
    public static final String AP_BILL_MATCH       = "AP.BILL.MATCH";
    public static final String AP_BILL_POST        = "AP.BILL.POST";
    public static final String AP_PAYMENT_MAKE     = "AP.PAYMENT.MAKE";
    public static final String AP_DEBITNOTE_RAISE  = "AP.DEBITNOTE.RAISE";
    public static final String AP_OPENING_SET      = "AP.OPENING.SET";

    // ---- Cash & Bank (ADR-0016 D-13) ----
    public static final String CASH_ACCOUNT_CREATE     = "CASH.ACCOUNT.CREATE";
    public static final String CASH_ACCOUNT_UPDATE     = "CASH.ACCOUNT.UPDATE";
    public static final String CASH_TRANSFER_RECORD    = "CASH.TRANSFER.RECORD";
    public static final String CASH_ENTRY_RECORD       = "CASH.ENTRY.RECORD";
    public static final String CASH_SETTLEMENT_RECORD  = "CASH.SETTLEMENT.RECORD";
    public static final String CHEQUE_REGISTER         = "CHEQUE.REGISTER";
    public static final String CHEQUE_CLEAR            = "CHEQUE.CLEAR";
    public static final String CHEQUE_CANCEL           = "CHEQUE.CANCEL";
    public static final String CASH_RECONCILE_OPEN     = "CASH.RECONCILE.OPEN";
    public static final String CASH_RECONCILE_MARK     = "CASH.RECONCILE.MARK";
    public static final String CASH_RECONCILE_COMPLETE = "CASH.RECONCILE.COMPLETE";

    // ---- VAT / Tax module (ADR-0017 D-13) ----
    public static final String VAT_RETURN_PREPARE  = "VAT.RETURN.PREPARE";
    public static final String VAT_RETURN_FILE     = "VAT.RETURN.FILE";
    public static final String VAT_ADJUST          = "VAT.ADJUST";
    public static final String WHT_TYPE_MANAGE     = "WHT.TYPE.MANAGE";
    public static final String WHT_CAPTURE         = "WHT.CAPTURE";

    // ---- Reporting module (ADR-0018 D-11) ----
    public static final String REPORT_EXPORT = "REPORT.EXPORT";

    // ---- Year-End Close (ADR-0019 D-12) ----
    // Both close and reopen emit this action; reopen distinguishes via detail("action","reopen").
    public static final String GL_YEAR_CLOSE = "GL.YEAR.CLOSE";

    // ---- Sales Orders / Order-to-Cash (ADR-0021) ----
    public static final String QUOTATION_CREATE   = "SALES.QUOTE.CREATE";
    public static final String QUOTATION_SEND     = "SALES.QUOTE.SEND";
    public static final String QUOTATION_ACCEPT   = "SALES.QUOTE.ACCEPT";
    public static final String QUOTATION_REJECT   = "SALES.QUOTE.REJECT";
    public static final String SO_CREATE          = "SALES.ORDER.CREATE";
    public static final String SO_LINE_ADD        = "SALES.ORDER.LINE.ADD";
    public static final String SO_LINE_REMOVE     = "SALES.ORDER.LINE.REMOVE";
    public static final String SO_CONFIRM         = "SALES.ORDER.CONFIRM";
    public static final String SO_CANCEL          = "SALES.ORDER.CANCEL";
    public static final String DELIVERY_CREATE    = "SALES.DELIVERY.CREATE";
    public static final String DELIVERY_INVOICE   = "SALES.DELIVERY.INVOICE";
    public static final String RETURN_CREATE      = "SALES.RETURN.CREATE";

    // ---- Approvals engine (ADR-0022) ----
    public static final String APPROVAL_POLICY_CREATE     = "APPROVAL.POLICY.CREATE";
    public static final String APPROVAL_POLICY_UPDATE     = "APPROVAL.POLICY.UPDATE";
    public static final String APPROVAL_POLICY_DEACTIVATE = "APPROVAL.POLICY.DEACTIVATE";
    public static final String APPROVAL_REQUEST_SUBMIT    = "APPROVAL.REQUEST.SUBMIT";
    public static final String APPROVAL_STEP_DECIDE       = "APPROVAL.STEP.DECIDE";
    public static final String APPROVAL_REQUEST_RECALL    = "APPROVAL.REQUEST.RECALL";
    public static final String APPROVAL_REQUEST_CANCEL    = "APPROVAL.REQUEST.CANCEL";
    // ---- Multi-level BOM (ADR-0026) ----
    public static final String BOM_CREATE          = "BOM.CREATE";
    public static final String BOM_UPDATE          = "BOM.UPDATE";
    public static final String BOM_ACTIVATE        = "BOM.ACTIVATE";
    public static final String BOM_SUPERSEDE       = "BOM.SUPERSEDE";
    public static final String BOM_ARCHIVE         = "BOM.ARCHIVE";
    public static final String BOM_COMPONENT_ADD   = "BOM.COMPONENT.ADD";
    public static final String BOM_COMPONENT_UPDATE = "BOM.COMPONENT.UPDATE";
    public static final String BOM_COMPONENT_REMOVE = "BOM.COMPONENT.REMOVE";
    public static final String BOM_PROMOTE_RECIPE  = "BOM.PROMOTE_RECIPE";
    // ---- Cost-centre / Accounting-dimension framework (ADR-0025) ----
    public static final String COSTING_DIMENSION_CREATE          = "COSTING.DIMENSION.CREATE";
    public static final String COSTING_VALUE_CREATE              = "COSTING.VALUE.CREATE";
    public static final String COSTING_VALUE_UPDATE              = "COSTING.VALUE.UPDATE";
    public static final String COSTING_VALUE_DEACTIVATE          = "COSTING.VALUE.DEACTIVATE";
    public static final String COSTING_DIMENSION_MANDATORY_SET   = "COSTING.DIMENSION.MANDATORY.SET";

    // --- inventory-depth (ADR-0028) ---
    public static final String STOCK_LOCATION_CREATE      = "STOCK.LOCATION.CREATE";
    public static final String STOCK_LOCATION_UPDATE      = "STOCK.LOCATION.UPDATE";
    public static final String STOCK_LOCATION_DEACTIVATE  = "STOCK.LOCATION.DEACTIVATE";
    public static final String STOCK_LOCATION_SET_DEFAULT = "STOCK.LOCATION.SET_DEFAULT";
    public static final String STOCK_TRANSFER_CREATE      = "STOCK.TRANSFER.CREATE";
    public static final String STOCK_TRANSFER_DISPATCH    = "STOCK.TRANSFER.DISPATCH";
    public static final String STOCK_TRANSFER_RECEIVE     = "STOCK.TRANSFER.RECEIVE";
    public static final String STOCK_TRANSFER_COMPLETE    = "STOCK.TRANSFER.COMPLETE";
    public static final String STOCK_TRANSFER_CANCEL      = "STOCK.TRANSFER.CANCEL";
    public static final String STOCK_COUNT_CREATE         = "STOCK.COUNT.CREATE";
    public static final String STOCK_COUNT_FREEZE         = "STOCK.COUNT.FREEZE";
    public static final String STOCK_COUNT_ENTER          = "STOCK.COUNT.ENTER";
    public static final String STOCK_COUNT_POST           = "STOCK.COUNT.POST";
    public static final String STOCK_COUNT_CANCEL         = "STOCK.COUNT.CANCEL";
    public static final String STOCK_BATCH_RECEIVE        = "STOCK.BATCH.RECEIVE";
    public static final String STOCK_SERIAL_RECEIVE       = "STOCK.SERIAL.RECEIVE";
    public static final String STOCK_SERIAL_ISSUE         = "STOCK.SERIAL.ISSUE";
    public static final String STOCK_SERIAL_RETURN        = "STOCK.SERIAL.RETURN";
    // ---- Fixed Assets (ADR-0030) ----
    public static final String FA_CATEGORY_CREATE    = "FA.CATEGORY.CREATE";
    public static final String FA_CATEGORY_UPDATE    = "FA.CATEGORY.UPDATE";
    public static final String FA_CATEGORY_ARCHIVE   = "FA.CATEGORY.ARCHIVE";
    public static final String FA_ASSET_REGISTER     = "FA.ASSET.REGISTER";
    public static final String FA_ASSET_UPDATE       = "FA.ASSET.UPDATE";
    public static final String FA_ASSET_IN_SERVICE   = "FA.ASSET.IN_SERVICE";
    public static final String FA_ASSET_TRANSFER     = "FA.ASSET.TRANSFER";
    public static final String FA_ASSET_DISPOSE      = "FA.ASSET.DISPOSE";
    public static final String FA_ASSET_WRITE_OFF    = "FA.ASSET.WRITE_OFF";
    public static final String FA_ASSET_REVALUE      = "FA.ASSET.REVALUE";
    public static final String FA_DEPRECIATION_RUN   = "FA.DEPRECIATION.RUN";
    // --- procurement-depth (ADR-0027) ---
    public static final String REQUISITION_CREATE   = "PURCHASE.REQUISITION.CREATE";
    public static final String REQUISITION_SUBMIT   = "PURCHASE.REQUISITION.SUBMIT";
    public static final String REQUISITION_APPROVE  = "PURCHASE.REQUISITION.APPROVE";
    public static final String REQUISITION_REJECT   = "PURCHASE.REQUISITION.REJECT";
    public static final String REQUISITION_CONVERT  = "PURCHASE.REQUISITION.CONVERT";
    public static final String REQUISITION_CANCEL   = "PURCHASE.REQUISITION.CANCEL";
    public static final String RFQ_CREATE           = "PURCHASE.RFQ.CREATE";
    public static final String RFQ_SEND             = "PURCHASE.RFQ.SEND";
    public static final String RFQ_AWARD            = "PURCHASE.RFQ.AWARD";
    public static final String RFQ_CANCEL           = "PURCHASE.RFQ.CANCEL";
    public static final String SUPPLIER_QUOTE_CAPTURE = "PURCHASE.QUOTE.CAPTURE";
    public static final String PO_APPROVE           = "PURCHASE.ORDER.APPROVE";
    public static final String PO_REJECT            = "PURCHASE.ORDER.REJECT";
    public static final String LANDED_COST_CREATE   = "PURCHASE.LANDEDCOST.CREATE";
    public static final String LANDED_COST_CONFIRM  = "PURCHASE.LANDEDCOST.CONFIRM";
    public static final String PURCHASE_RETURN_CREATE  = "PURCHASE.RETURN.CREATE";
    public static final String PURCHASE_RETURN_CONFIRM = "PURCHASE.RETURN.CONFIRM";
    public static final String PURCHASE_SETTINGS_UPDATE = "PURCHASE.SETTINGS.UPDATE";
    // ---- HR & Payroll (ADR-0032) ----
    public static final String HR_DEPARTMENT_CREATE    = "HR.DEPARTMENT.CREATE";
    public static final String HR_DEPARTMENT_UPDATE    = "HR.DEPARTMENT.UPDATE";
    public static final String HR_EMPLOYEE_CREATE      = "HR.EMPLOYEE.CREATE";
    public static final String HR_EMPLOYEE_UPDATE      = "HR.EMPLOYEE.UPDATE";
    public static final String HR_EMPLOYEE_ARCHIVE     = "HR.EMPLOYEE.ARCHIVE";
    public static final String HR_EMPLOYEE_NOK_ADD        = "HR.EMPLOYEE.NOK.ADD";
    public static final String HR_EMPLOYEE_NOK_UPDATE     = "HR.EMPLOYEE.NOK.UPDATE";
    public static final String HR_EMPLOYEE_NOK_DEACTIVATE = "HR.EMPLOYEE.NOK.DEACTIVATE";
    public static final String HR_CONTRACT_CREATE      = "HR.CONTRACT.CREATE";
    public static final String HR_CONTRACT_TERMINATE   = "HR.CONTRACT.TERMINATE";
    public static final String HR_PAYCOMPONENT_CREATE  = "HR.PAYCOMPONENT.CREATE";
    public static final String HR_PAYCOMPONENT_UPDATE  = "HR.PAYCOMPONENT.UPDATE";
    public static final String HR_LEAVE_TYPE_CREATE    = "HR.LEAVE.TYPE.CREATE";
    public static final String HR_LEAVE_TYPE_UPDATE    = "HR.LEAVE.TYPE.UPDATE";
    public static final String HR_LEAVE_REQUEST_SUBMIT = "HR.LEAVE.REQUEST.SUBMIT";
    public static final String HR_LEAVE_REQUEST_DECIDE = "HR.LEAVE.REQUEST.DECIDE";
    public static final String HR_LOAN_CREATE          = "HR.LOAN.CREATE";
    public static final String HR_LOAN_APPROVE         = "HR.LOAN.APPROVE";
    public static final String HR_STATUTORY_PAYE_CREATE  = "HR.STATUTORY.PAYE.CREATE";
    public static final String HR_STATUTORY_RATE_CREATE  = "HR.STATUTORY.RATE.CREATE";
    public static final String HR_PAYROLL_RUN_CREATE   = "HR.PAYROLL.RUN.CREATE";
    public static final String HR_PAYROLL_RUN_CALCULATE = "HR.PAYROLL.RUN.CALCULATE";
    public static final String HR_PAYROLL_RUN_APPROVE  = "HR.PAYROLL.RUN.APPROVE";
    public static final String HR_PAYROLL_RUN_POST     = "HR.PAYROLL.RUN.POST";
    public static final String HR_PAYROLL_RUN_DISBURSE = "HR.PAYROLL.RUN.DISBURSE";
    public static final String HR_PAYROLL_RUN_REVERSE  = "HR.PAYROLL.RUN.REVERSE";

    // --- crm (ADR-0031) ---
    public static final String CRM_LEAD_CREATE       = "CRM.LEAD.CREATE";
    public static final String CRM_LEAD_UPDATE       = "CRM.LEAD.UPDATE";
    public static final String CRM_LEAD_CONTACT      = "CRM.LEAD.CONTACT";
    public static final String CRM_LEAD_QUALIFY      = "CRM.LEAD.QUALIFY";
    public static final String CRM_LEAD_DISQUALIFY   = "CRM.LEAD.DISQUALIFY";
    public static final String CRM_OPPORTUNITY_CREATE        = "CRM.OPPORTUNITY.CREATE";
    public static final String CRM_OPPORTUNITY_UPDATE        = "CRM.OPPORTUNITY.UPDATE";
    public static final String CRM_OPPORTUNITY_LINE_ADD      = "CRM.OPPORTUNITY.LINE.ADD";
    public static final String CRM_OPPORTUNITY_STAGE_ADVANCE = "CRM.OPPORTUNITY.STAGE.ADVANCE";
    public static final String CRM_OPPORTUNITY_WIN           = "CRM.OPPORTUNITY.WIN";
    public static final String CRM_OPPORTUNITY_LOSE          = "CRM.OPPORTUNITY.LOSE";
    public static final String CRM_OPPORTUNITY_CONVERT       = "CRM.OPPORTUNITY.CONVERT";
    public static final String CRM_ACTIVITY_CREATE   = "CRM.ACTIVITY.CREATE";
    public static final String CRM_ACTIVITY_COMPLETE = "CRM.ACTIVITY.COMPLETE";
    public static final String CRM_STAGE_CREATE      = "CRM.STAGE.CREATE";
    public static final String CRM_STAGE_UPDATE      = "CRM.STAGE.UPDATE";
    public static final String CRM_STAGE_DEACTIVATE  = "CRM.STAGE.DEACTIVATE";

    // ---- Sales Depth (ADR-0029) ----
    // POS till
    public static final String POS_TILL_CREATE           = "POS.TILL.CREATE";
    public static final String POS_TILL_UPDATE           = "POS.TILL.UPDATE";
    public static final String POS_TILL_DEACTIVATE       = "POS.TILL.DEACTIVATE";
    // POS session lifecycle
    public static final String POS_SESSION_OPEN          = "POS.SESSION.OPEN";
    public static final String POS_SESSION_PAYOUT        = "POS.SESSION.PAYOUT";
    public static final String POS_SESSION_CLOSE         = "POS.SESSION.CLOSE";
    public static final String POS_SESSION_RECONCILE     = "POS.SESSION.RECONCILE";
    // POS sale / refund
    public static final String POS_SALE_RING             = "POS.SALE.RING";
    public static final String POS_SALE_FINALISE         = "POS.SALE.FINALISE";
    public static final String POS_SALE_REVERSE          = "POS.SALE.REVERSE";
    public static final String POS_REFUND                = "POS.REFUND";
    // Pricing rules
    public static final String PRICING_TIER_CREATE       = "PRICING.TIER.CREATE";
    public static final String PRICING_TIER_UPDATE       = "PRICING.TIER.UPDATE";
    public static final String PRICING_TIER_DEACTIVATE   = "PRICING.TIER.DEACTIVATE";
    public static final String CUSTOMER_PRICE_CREATE     = "PRICING.CUSTOMER_PRICE.CREATE";
    public static final String CUSTOMER_PRICE_UPDATE     = "PRICING.CUSTOMER_PRICE.UPDATE";
    public static final String CUSTOMER_PRICE_DEACTIVATE = "PRICING.CUSTOMER_PRICE.DEACTIVATE";
    public static final String PROMOTION_CREATE          = "PRICING.PROMOTION.CREATE";
    public static final String PROMOTION_UPDATE          = "PRICING.PROMOTION.UPDATE";
    public static final String PROMOTION_DEACTIVATE      = "PRICING.PROMOTION.DEACTIVATE";
    // Drop-ship
    public static final String DROPSHIP_FLAG             = "DROPSHIP.FLAG";
    public static final String DROPSHIP_PO_RAISED        = "DROPSHIP.PO.RAISED";
    public static final String DROPSHIP_FULFILLED        = "DROPSHIP.FULFILLED";
    // Blanket orders
    public static final String BLANKET_ORDER_CREATE      = "BLANKET.ORDER.CREATE";
    public static final String BLANKET_ORDER_DRAW        = "BLANKET.ORDER.DRAW";
    public static final String BLANKET_ORDER_CANCEL      = "BLANKET.ORDER.CANCEL";
    public static final String BLANKET_ORDER_CALLOFF     = "BLANKET.ORDER.CALLOFF";
    public static final String BLANKET_ORDER_CLOSE       = "BLANKET.ORDER.CLOSE";
    // Standing orders
    public static final String STANDING_ORDER_CREATE     = "STANDING.ORDER.CREATE";
    public static final String STANDING_ORDER_PAUSE      = "STANDING.ORDER.PAUSE";
    public static final String STANDING_ORDER_RESUME     = "STANDING.ORDER.RESUME";
    public static final String STANDING_ORDER_CANCEL     = "STANDING.ORDER.CANCEL";
    public static final String STANDING_ORDER_GENERATE   = "STANDING.ORDER.GENERATE";
    public static final String STANDING_ORDER_GENERATED  = "STANDING.ORDER.GENERATED";

    // ---- Notifications (ADR-0024) ----
    public static final String NOTIFICATION_TYPE_TOGGLE = "NOTIFICATION.TYPE.TOGGLE";

    // --- projects / job-costing (ADR-0033) ---
    public static final String PROJECT_CREATE          = "PROJECT.CREATE";
    public static final String PROJECT_UPDATE          = "PROJECT.UPDATE";
    public static final String PROJECT_STATUS_CHANGE   = "PROJECT.STATUS.CHANGE";
    public static final String PROJECT_ARCHIVE         = "PROJECT.ARCHIVE";
    public static final String PROJECT_TASK_CREATE     = "PROJECT.TASK.CREATE";
    public static final String PROJECT_TASK_UPDATE     = "PROJECT.TASK.UPDATE";
    public static final String PROJECT_TASK_DEACTIVATE = "PROJECT.TASK.DEACTIVATE";
    public static final String PROJECT_TIMESHEET_RECORD = "PROJECT.TIMESHEET.RECORD";
    public static final String PROJECT_MATERIAL_ISSUE  = "PROJECT.MATERIAL.ISSUE";

    // ---- Budgeting & Management Accounting (ADR-0034) ----
    public static final String BUDGET_CREATE            = "BUDGET.CREATE";
    public static final String BUDGET_VERSION_CREATE    = "BUDGET.VERSION.CREATE";
    public static final String BUDGET_VERSION_SUBMIT    = "BUDGET.VERSION.SUBMIT";
    public static final String BUDGET_VERSION_RECALL    = "BUDGET.VERSION.RECALL";
    public static final String BUDGET_VERSION_APPROVE   = "BUDGET.VERSION.APPROVE";
    public static final String BUDGET_VERSION_REJECT    = "BUDGET.VERSION.REJECT";

    // ---- Manufacturing / Production (ADR-0035) ----
    public static final String WORKORDER_CREATE           = "WORKORDER.CREATE";
    public static final String WORKORDER_UPDATE           = "WORKORDER.UPDATE";
    public static final String WORKORDER_RELEASE          = "WORKORDER.RELEASE";
    public static final String WORKORDER_CANCEL           = "WORKORDER.CANCEL";
    public static final String WORKORDER_ISSUE_COMPONENTS = "WORKORDER.ISSUE_COMPONENTS";
    public static final String WORKORDER_APPLY_COST       = "WORKORDER.APPLY_COST";
    public static final String WORKORDER_COMPLETE         = "WORKORDER.COMPLETE";
    public static final String WORKORDER_CLOSE            = "WORKORDER.CLOSE";

    // ---- FX / Multi-currency (ADR-0036 D-10) ----
    /** Emitted when a currency exchange rate is created or corrected. */
    public static final String FX_RATE_SET             = "FX.RATE.SET";
    /** Emitted when a period-end FX revaluation run is posted. */
    public static final String FX_REVALUATION_RUN      = "FX.REVALUATION.RUN";

    // ---- PaymentTerms master (D-2, ADR-0040) ----
    public static final String PAYMENTTERMS_CREATE  = "PAYMENTTERMS.CREATE";
    public static final String PAYMENTTERMS_UPDATE  = "PAYMENTTERMS.UPDATE";
    public static final String PAYMENTTERMS_ARCHIVE = "PAYMENTTERMS.ARCHIVE";
    public static final String PAYMENTTERMS_RESTORE = "PAYMENTTERMS.RESTORE";

    // ---- SupplierBankAccount (D-4, ADR-0040) ----
    public static final String SUPPLIER_BANK_ACCOUNT_CREATE     = "SUPPLIER.BANK_ACCOUNT.CREATE";
    public static final String SUPPLIER_BANK_ACCOUNT_UPDATE     = "SUPPLIER.BANK_ACCOUNT.UPDATE";
    public static final String SUPPLIER_BANK_ACCOUNT_ARCHIVE    = "SUPPLIER.BANK_ACCOUNT.ARCHIVE";
    public static final String SUPPLIER_BANK_ACCOUNT_RESTORE    = "SUPPLIER.BANK_ACCOUNT.RESTORE";
    public static final String SUPPLIER_BANK_ACCOUNT_SET_DEFAULT = "SUPPLIER.BANK_ACCOUNT.SET_DEFAULT";

    // ---- Currency enablement (ADR-0039 D-9 / OQ-CCY-08) ----
    /**
     * Emitted when the company base currency is changed. Gated by admin permission;
     * only allowed when no GL journal_entries exist for the company (OQ-CCY-08).
     */
    public static final String COMPANY_BASE_CURRENCY_CHANGE = "COMPANY.BASE_CURRENCY.CHANGE";

    /**
     * Emitted when an admin re-provisions all company-scoped defaults for an existing company
     * (idempotent heal). Scoped to {@code COMPANY.MANAGE} on the target company.
     */
    public static final String COMPANY_PROVISION_DEFAULTS = "COMPANY.PROVISION_DEFAULTS";

    // ---- Party contacts & addresses (ADR-0040 D-3) ----
    public static final String CUSTOMER_CONTACT_ADD      = "CUSTOMER.CONTACT.ADD";
    public static final String CUSTOMER_CONTACT_UPDATE   = "CUSTOMER.CONTACT.UPDATE";
    public static final String CUSTOMER_CONTACT_DEACTIVATE = "CUSTOMER.CONTACT.DEACTIVATE";
    public static final String CUSTOMER_ADDRESS_ADD      = "CUSTOMER.ADDRESS.ADD";
    public static final String CUSTOMER_ADDRESS_UPDATE   = "CUSTOMER.ADDRESS.UPDATE";
    public static final String CUSTOMER_ADDRESS_DEACTIVATE = "CUSTOMER.ADDRESS.DEACTIVATE";
    public static final String SUPPLIER_CONTACT_ADD      = "SUPPLIER.CONTACT.ADD";
    public static final String SUPPLIER_CONTACT_UPDATE   = "SUPPLIER.CONTACT.UPDATE";
    public static final String SUPPLIER_CONTACT_DEACTIVATE = "SUPPLIER.CONTACT.DEACTIVATE";
    public static final String SUPPLIER_ADDRESS_ADD      = "SUPPLIER.ADDRESS.ADD";
    public static final String SUPPLIER_ADDRESS_UPDATE   = "SUPPLIER.ADDRESS.UPDATE";
    public static final String SUPPLIER_ADDRESS_DEACTIVATE = "SUPPLIER.ADDRESS.DEACTIVATE";

    // ---- Barcode Symbology Rules (ADR-0044 D-1a / BR-9) ----
    public static final String SYMBOLOGY_RULE_CREATE  = "SYMBOLOGY.RULE.CREATE";
    public static final String SYMBOLOGY_RULE_UPDATE  = "SYMBOLOGY.RULE.UPDATE";
    public static final String SYMBOLOGY_RULE_ARCHIVE = "SYMBOLOGY.RULE.ARCHIVE";
}
