package com.erp.modules.purchases.service;

import com.erp.modules.gl.service.ChartOfAccountService;
import com.erp.modules.gl.service.FiscalCalendarService;
import com.erp.modules.gl.service.GlConfigService;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.domain.entity.UserBranch;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.iam.repository.UserBranchRepository;
import com.erp.modules.parties.domain.dto.CreateSupplierRequest;
import com.erp.modules.parties.domain.dto.SupplierDto;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.domain.enums.SupplierKind;
import com.erp.modules.parties.service.SupplierService;
import com.erp.modules.products.domain.dto.CreateProductRequest;
import com.erp.modules.products.domain.dto.CreateUnitOfMeasureRequest;
import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.products.service.ProductService;
import com.erp.modules.products.service.UnitOfMeasureService;
import com.erp.modules.purchases.domain.dto.AddPurchaseOrderLineRequest;
import com.erp.modules.purchases.domain.dto.CreateGoodsReceiptRequest;
import com.erp.modules.purchases.domain.dto.CreatePurchaseOrderRequest;
import com.erp.modules.purchases.domain.dto.DirectGoodsReceiptLineRequest;
import com.erp.modules.purchases.domain.dto.DirectGoodsReceiptRequest;
import com.erp.modules.purchases.domain.dto.GoodsReceiptDto;
import com.erp.modules.purchases.domain.dto.GoodsReceiptLineRequest;
import com.erp.modules.purchases.domain.dto.PurchaseOrderDto;
import com.erp.modules.purchases.domain.dto.VoidGoodsReceiptRequest;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Shared fixture for the purchase-report ITs: one company with two branches, two suppliers, a root
 * user and a NON-root clerk assigned to branch A only.
 *
 * <p>Orders and receipts go through the real services, so the rows the reports read are exactly the
 * rows production writes. Their timestamps are then moved with a direct UPDATE — the services stamp
 * {@code now()}, and a period report cannot be tested without receipts on known days. Supplier bills
 * and purchase returns are inserted directly: their own services post to the GL and raise AP debit
 * notes, none of which these read-only reports look at.
 */
abstract class PurchaseReportFixture extends PostgresIntegrationTest {

    /** The company time zone (the column default). Report windows are computed in it. */
    static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    @Autowired protected OrganisationRepository organisations;
    @Autowired protected CompanyRepository      companies;
    @Autowired protected BranchRepository       branches;
    @Autowired protected AppUserRepository      users;
    @Autowired protected UserBranchRepository   userBranches;
    @Autowired protected PasswordEncoder        passwordEncoder;
    @Autowired protected IamTestData            testData;

    @Autowired protected ChartOfAccountService  chartOfAccountService;
    @Autowired protected FiscalCalendarService  fiscalCalendarService;
    @Autowired protected GlConfigService        glConfigService;

    @Autowired protected SupplierService        supplierService;
    @Autowired protected ProductService         productService;
    @Autowired protected UnitOfMeasureService   unitService;
    @Autowired protected PurchaseOrderService   poService;
    @Autowired protected GoodsReceiptService    grService;
    @Autowired protected DirectGoodsReceiptService directGrService;
    @Autowired protected JdbcTemplate           jdbc;

    protected Organisation org;
    protected Company company;
    protected Branch  branchA;
    protected Branch  branchB;
    protected AppUser root;
    protected AppUser clerk;
    protected String  pcsUid;
    protected SupplierDto supplier1;
    protected SupplierDto supplier2;

    private final AtomicInteger seq = new AtomicInteger();

    @BeforeEach
    void setUpPurchaseFixture() {
        testData.clearAll();

        org     = organisations.save(new Organisation("PurchRpt IT Org"));
        company = companies.save(new Company(org, "PRPT", "PurchRpt IT Co"));
        branchA = branches.save(new Branch(company, "PRPT-A", "Branch A"));
        branchB = branches.save(new Branch(company, "PRPT-B", "Branch B"));

        AppUser r = new AppUser("purchrpt_root", passwordEncoder.encode("PurchRpt@1!Xx"), "PR Root");
        r.setRoot(true);
        r.setOrganisationId(org.getId());
        root = users.save(r);

        AppUser c = new AppUser("purchrpt_clerk", passwordEncoder.encode("PurchRpt@1!Xx"), "PR Clerk");
        c.setOrganisationId(org.getId());
        clerk = users.save(c);
        testData.seedMembership(clerk.getUid(), company.getUid());
        userBranches.save(new UserBranch(clerk.getId(), branchA, clerk.getId()));

        asRoot(branchA);
        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());

        pcsUid = unitService.create(new CreateUnitOfMeasureRequest(company.getUid(), "PCS", "Pieces")).uid();
        supplier1 = supplier("Alpha Traders");
        supplier2 = supplier("Beta Wholesale");
    }

    @AfterEach
    void clearPurchaseFixtureContext() {
        RequestContext.clear();
    }

    // ---- context ------------------------------------------------------------

    protected void asRoot(Branch branch) {
        RequestContext.set(new RequestContext.Principal(
                root.getId(), "purchrpt_root", true, company.getId(), branch.getId(), null, org.getId()));
    }

    /** A non-root member assigned to branch A only — root would short-circuit the branch guard. */
    protected void asClerk() {
        RequestContext.set(new RequestContext.Principal(
                clerk.getId(), "purchrpt_clerk", false, company.getId(), branchA.getId(), null,
                org.getId()));
    }

    // ---- master data ----------------------------------------------------------

    protected SupplierDto supplier(String name) {
        return supplierService.create(new CreateSupplierRequest(
                company.getId(), PartyType.INDIVIDUAL, name,
                null, null, null, null, null, null, null, null, null, null, null, null,
                SupplierKind.GOODS, null, null));
    }

    protected ProductDto product(String name) {
        return productService.create(new CreateProductRequest(
                company.getUid(), null, name, null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD,
                null, null, null, null, null, null, null, null, null));
    }

    // ---- purchasing -------------------------------------------------------------

    /** A placed (ORDERED) PO with one line per {@code lines} entry: {productUid, qty, unitCost}. */
    protected PurchaseOrderDto placeOrder(SupplierDto supplier, String currency, Object[]... lines) {
        List<AddPurchaseOrderLineRequest> reqs = java.util.Arrays.stream(lines)
                .map(l -> new AddPurchaseOrderLineRequest((String) l[0], pcsUid,
                        new BigDecimal(l[1].toString()), new BigDecimal(l[2].toString()), null))
                .toList();
        PurchaseOrderDto draft = poService.create(new CreatePurchaseOrderRequest(
                company.getUid(), supplier.uid(), currency, null, null, reqs));
        return poService.placeOrder(draft.uid());
    }

    protected GoodsReceiptDto receive(PurchaseOrderDto po, int lineIndex, String qty) {
        return grService.createAndReceive(new CreateGoodsReceiptRequest(po.uid(), null,
                List.of(new GoodsReceiptLineRequest(po.lines().get(lineIndex).uid(), new BigDecimal(qty)))));
    }

    protected GoodsReceiptDto receiveDirect(SupplierDto supplier, ProductDto product, String qty,
                                            String unitCost) {
        return directGrService.receiveDirect(new DirectGoodsReceiptRequest(
                company.getUid(), supplier.uid(), null, "cash purchase",
                List.of(new DirectGoodsReceiptLineRequest(product.uid(), pcsUid,
                        new BigDecimal(qty), new BigDecimal(unitCost), null))));
    }

    protected void voidReceipt(GoodsReceiptDto gr) {
        grService.voidReceipt(gr.uid(), new VoidGoodsReceiptRequest("wrong delivery"));
    }

    // ---- moving timestamps onto known days ---------------------------------------

    /** Noon on {@code day} in the company zone — safely inside that day's report window. */
    protected static OffsetDateTime noon(LocalDate day) {
        return day.atTime(12, 0).atZone(ZONE).toOffsetDateTime();
    }

    protected void setReceivedAt(GoodsReceiptDto gr, LocalDate day) {
        jdbc.update("UPDATE goods_receipts SET received_at = ? WHERE uid = ?", noon(day), gr.uid());
    }

    protected void setVoidedAt(GoodsReceiptDto gr, LocalDate day) {
        jdbc.update("UPDATE goods_receipts SET voided_at = ? WHERE uid = ?", noon(day), gr.uid());
    }

    protected void setOrderedAt(PurchaseOrderDto po, LocalDate day) {
        jdbc.update("UPDATE purchase_orders SET ordered_at = ? WHERE uid = ?", noon(day), po.uid());
    }

    // ---- directly inserted documents ------------------------------------------------

    protected String uid() {
        // 26 chars, unique per fixture: the reports never parse it.
        return String.format("TST%023d", seq.incrementAndGet());
    }

    /**
     * A CONFIRMED purchase return of {@code qty} of the given receipt's first line, at that line's
     * receipt cost, confirmed on {@code day}.
     */
    protected void confirmedReturn(GoodsReceiptDto gr, String qty, LocalDate day) {
        var line = gr.lines().get(0);
        BigDecimal q = new BigDecimal(qty);
        BigDecimal value = q.multiply(line.unitCostAmount());
        Long grId = jdbc.queryForObject("SELECT id FROM goods_receipts WHERE uid = ?", Long.class, gr.uid());
        Long supplierId = jdbc.queryForObject("SELECT supplier_id FROM goods_receipts WHERE uid = ?",
                Long.class, gr.uid());
        Long branchId = jdbc.queryForObject("SELECT branch_id FROM goods_receipts WHERE uid = ?",
                Long.class, gr.uid());
        String retUid = uid();
        jdbc.update("""
                INSERT INTO purchase_returns (uid, company_id, branch_id, return_number, status,
                    goods_receipt_id, goods_receipt_uid, supplier_id, supplier_code, supplier_name,
                    reason, net_amount, vat_amount, gross_amount, currency, confirmed_at)
                VALUES (?, ?, ?, ?, 'CONFIRMED', ?, ?, ?, 'SUP', 'Supplier', 'damaged', ?, 0, ?, 'TZS', ?)
                """, retUid, company.getId(), branchId, "PRET-" + seq.incrementAndGet(), grId, gr.uid(),
                supplierId, value, value, noon(day));
        Long retId = jdbc.queryForObject("SELECT id FROM purchase_returns WHERE uid = ?", Long.class, retUid);
        jdbc.update("""
                INSERT INTO purchase_return_lines (uid, purchase_return_id, goods_receipt_line_id,
                    goods_receipt_line_uid, company_id, branch_id, line_no, product_id, product_code,
                    product_name, unit_id, unit_name, returned_qty, returned_qty_in_base,
                    unit_cost_amount, line_value_amount, currency)
                VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'TZS')
                """, uid(), retId, line.id(), line.uid(), company.getId(), branchId, line.productId(),
                line.productCode(), line.productName(), line.unitId(), line.unitName(), q, q,
                line.unitCostAmount(), value);
    }

    /**
     * A posted (APPROVED) supplier bill dated {@code billDate} with one line claimed against the
     * given receipt line, billed at {@code unitPrice}. {@code outstanding} is what is left unpaid.
     */
    protected void bill(SupplierDto supplier, Branch branch, GoodsReceiptDto gr, String billedQty,
                        String unitPrice, LocalDate billDate, String outstanding, String status) {
        var line = gr.lines().get(0);
        BigDecimal q = new BigDecimal(billedQty);
        BigDecimal net = q.multiply(new BigDecimal(unitPrice));
        BigDecimal vat = net.multiply(new BigDecimal("0.18"));
        BigDecimal gross = net.add(vat);
        String billUid = uid();
        int n = seq.incrementAndGet();
        jdbc.update("""
                INSERT INTO supplier_bills (uid, company_id, branch_id, supplier_id, bill_number,
                    supplier_invoice_no, source, bill_date, due_date, net_amount, vat_amount,
                    gross_amount, outstanding_amount, currency, status)
                VALUES (?, ?, ?, ?, ?, ?, 'BILL', ?, ?, ?, ?, ?, ?, 'TZS', ?)
                """, billUid, company.getId(), branch.getId(), supplier.id(),
                "DRAFT".equals(status) ? null : "BILL-" + n, "INV-" + n, billDate, billDate.plusDays(30),
                net, vat, gross, new BigDecimal(outstanding), status);
        Long billId = jdbc.queryForObject("SELECT id FROM supplier_bills WHERE uid = ?", Long.class, billUid);
        jdbc.update("""
                INSERT INTO supplier_bill_lines (uid, supplier_bill_id, company_id, branch_id, line_no,
                    product_id, gr_line_uid, description, billed_qty, unit_cost_amount,
                    line_net_amount, currency)
                VALUES (?, ?, ?, ?, 1, ?, ?, 'billed goods', ?, ?, ?, 'TZS')
                """, uid(), billId, company.getId(), branch.getId(), line.productId(), line.uid(), q,
                new BigDecimal(unitPrice), net);
    }
}
