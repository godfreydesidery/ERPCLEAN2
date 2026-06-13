# FX adversarial-review findings (feat/fx-multicurrency)

26 findings; 13 confirmed BLOCKER/HIGH; 2 refuted.

## CONFIRMED BLOCKER/HIGH (must fix)

### 1. [BLOCKER] AP revaluation journal is unbalanced â€” every AP reval control leg posts on the same side as its aggregate FX leg, so Î£base != 0 and the FX_REVALUATION journal is silently dropped

CONFIRMED â€” genuine BLOCKER on a supported (multi-currency) path. Verified on feat/fx-multicurrency (HEAD 54f6f5e).

ROOT CAUSE â€” the AP direction flip is applied TWICE:
1. FxRevaluationRunServiceImpl.computeRevalLines stamps the AP adjustment with the OPPOSITE sign to AR: AR adjustment = revaluedBase - carryingBase (line 402); AP adjustment = carryingBase - revaluedBase (line 447). So an AP economic loss (we owe more in base) is already encoded as a NEGATIVE adjustment.
2. postRevaluationJournal then flips the control-leg SIDE again for AP. For a gain (adjustment>0): AR control = DR (line 491), but AP control = CR (line 495). For a loss (adjustment<0): AR control = CR (line 503), but AP control = DR (line 506).

The aggregate FX legs (built ONCE from totalGain/totalLoss, which post() lines 217-221 and preview() lines 155-159 derive purely from rl.adjustment sign, blind to sourceType) are: CR UNREALIZED_FX_GAIN = totalGain (lines 528-531) and DR UNREALIZED_FX_LOSS = totalLoss (lines 532-535).

To balance, each control leg must sit OPPOSITE its matching aggregate leg. AR satisfies this (gain: DR control vs CR fxGain; loss: CR control vs DR fxLoss â†’ balanced for any AR mix). AP does NOT: gain emits CR control AND CR fxGain (both credit); loss emits DR control AND DR fxLoss (both debit).

SYMBOLIC PROOF (pure-AP run, G = Î£ AP-gain absAdj, L = Î£ AP-loss absAdj):
Î£DR = L (loss control, line 506) + L (fxLoss, line 533) = 2L
Î£CR = G (gain control, line 495) + G (fxGain, line 529) = 2G
Balanced only if G==L. Imbalance = 2|G-L| = 2Ã—|net AP adjustment|.

FINDING'S CONCRETE TRACE CONFIRMED â€” AP-only loss (carrying 1000, revalued 1200): adjustment = 1000-1200 = -200 â†’ totalLoss=200 â†’ DR AP control 200 (line 506) + DR fxLoss 200 (line 533) â†’ Î£DR=400, Î£CR=0. AP-only gain (carrying 1200, revalued 1000): adjustment=+200 â†’ totalGain=200 â†’ CR AP control 200 (line 495) + CR fxGain 200 (line 529) â†’ Î£CR=400, Î£DR=0. Mixed AR+AP: AR portion balances, net imbalance = 2Ã—(net AP adjustment).

SILENT-DROP MECHANISM CONFIRMED â€” GLPostingServiceImpl.post lines 127-132 throws IllegalArgumentException ('Journal entry is unbalanced (BR-GL-01)') when totalDebit.compareTo(totalCredit)!=0. GLPostingSafeInvoker.postInNewTx (lines 174-181) catches all exceptions, logs a warning, and returns null. Back in post(), line 238 'if (glEntry != null)' is then false â†’ run is never set to POSTED (stays PREVIEWED), no glEntryUid is recorded, and NO unrealized FX is booked for AP â€” the period-end mark-to-market on payables is dropped without any hard failure. So both I-1 (Î£base debits != Î£base credits) and I-4 (open foreign AP not revalued; FX_REVALUATION journal cannot post) are violated.

DIRECTION ALSO CONCEPTUALLY WRONG â€” the in-code comments are themselves accounting-incorrect and the code follows them: line 489 says 'CR AP control (reduce liability)' but a credit INCREASES a payable; line 501 says 'DR AP control' for a loss (liability increased) but a debit REDUCES a payable. Correct mapping: AP gain (liability fell) â†’ DR AP control; AP loss (liability rose) â†’ CR AP control.

SUPPORTED & REACHABLE â€” SupplierBillRepository.findOpenForeignForRevaluation (lines 109-117) returns open foreign AP bills (status MATCHED/APPROVED/PARTIALLY_PAID, currency != base) and is invoked at FxRevaluationRunServiceImpl line 413. Any company with an open foreign supplier bill running a period-end reval hits this. Multi-currency is in scope, so this is a supported path, not dead code.

WHY GREEN TESTS MISSED IT â€” the sole IT, FxRevaluationRunServiceIT, only seeds ONE foreign AR invoice with spot>original (a single AR gain). It has no AP bill and no AP-revaluation case, and no mixed-run case. The 820-test suite therefore never exercises postRevaluationJournal's AP branches (lines 493-497, 505-508).

ENGINE INVARIANTS HOLD â€” GLPostingServiceImpl.post balance check (lines 127-132) is intact and correctly rejects the bad journal; I-1/I-5 hold at the engine and the base-currency identity path is unaffected (TZS bills are excluded by 'currency <> :baseCurrency'). The defect is solely in the AP reval poster.

MINIMAL CORRECT FIX â€” in FxRevaluationRunServiceImpl.postRevaluationJournal, flip the AP control-leg sides so they offset their aggregate FX leg. AP gain (adjustment>0): emit DR control absAdj (reduce payable) â€” change line 495 from (controlAccountId, ZERO, absAdj) to (controlAccountId, absAdj, ZERO). AP loss (adjustment<0): emit CR control absAdj (increase payable) â€” change line 506 from (controlAccountId, absAdj, ZERO) to (controlAccountId, ZERO, absAdj). After the fix, Î£DR-Î£CR = 0 for AR-only, AP-only, and mixed runs. Also correct the misleading comments at lines 489/501, and add an AP-only and a mixed-AR+AP revaluation IT to FxRevaluationRunServiceIT asserting Î£debit==Î£credit and run status POSTED/REVERSED.

---

### 2. [BLOCKER] Foreign credit-sale AR open item is created with rate=1 / null base amounts â€” realized FX uses rate=1 and revaluation treats face-as-base

CONFIRMED on the supported multi-currency credit-sale path. Verified on feat/fx-multicurrency (HEAD 54f6f5e).

ROOT CAUSE (proven in code):
- GL side debits AR control in BASE. GLPostingSafeInvoker.postSaleInNewTx converts net/vat to base (lines 96,102) and posts DR AR = balancingPlug(baseNet,baseVat) = baseGross (lines 106,118) in baseCurrency (line 97). For a $1000 @ 2500 invoice the SALES journal debits AR control ~2.5M base. SalesInvoiceServiceImpl.finalise stamps the immutable triple on the SALES invoice (lines 233-235).
- Sub-ledger side never gets the triple. ArSalePostedHandler.createOpenItemIfCredit builds `new ArInvoice(... receivable, totals.currency(), ...)` (lines 159-164) â€” face amount + currency only. The ArInvoice ctor (entity lines 116-132) sets originalAmount/outstandingAmount=face and leaves fxRate defaulting to BigDecimal.ONE (line 95) with baseOriginalAmount/baseOutstandingAmount/rateAt null (lines 98-110). The handler cannot stamp the triple because InvoicePostingTotalsDto (record, lines 21-43) exposes NO fxRate / baseGrossTotalAmount / rateAt. A repo-wide grep confirms the SALE open item is never backfilled: the only setBaseOriginalAmount/setFxRate calls on AR invoices are in ArReceiptServiceImpl (settlement decrement) and FxRevaluationRunServiceIT (hand-stamped OPENING_BALANCE test invoice).

DOWNSTREAM, both reachable in production:
(1) I-3 realized FX. ArReceiptServiceImpl line 199 invoiceRate = inv.getFxRate() (=1) â†’ baseRelieved = allocatedÃ—1 (lines 200-201); arCrBase credits AR control at face (line 324) while the SALES journal debited it at base â€” AR control never nets to zero across the lifecycle, and fxDelta = sumBaseRelieved âˆ’ sumBaseSettled (line 289) is computed off rate=1, not the original invoice rate. The receipt journal still balances internally (plug construction), so I-1 per-journal holds, but the realized-FX amount and AR-control sub-ledgerâ†”GL reconciliation are materially wrong.
(2) I-4/I-6 revaluation. ArInvoiceRepository.findOpenForeignForRevaluation (lines 107-115) selects this open item (currency <> base, status OPEN/PARTIAL). FxRevaluationRunServiceImpl lines 386-389 fall back baseOutstanding â†’ outstanding (face), so carryingBase=face, revaluedBase=faceÃ—spot (line 400), adjustment=faceÃ—(spotâˆ’1) (line 402) â€” a phantom unrealized gain/loss that silently treats the foreign open item as if booked at rate=1 (the I-6 fail-loud rule for foreign rates is bypassed).

Confirms the finding's scoping: there is NO end-to-end foreign-credit-sale IT and the only reval IT hand-stamps an OPENING_BALANCE invoice, so the 820 green tests never exercise this path. (Note: the finding's per-journal I-1 reasoning for the receipt/AP posters is sound â€” each journal balances by plug; the breakage is the lifecycle AR-control reconciliation + wrong realized/unrealized FX, not a single unbalanced entry. AP-revaluation symmetry is a separate finding and out of scope here.)

MINIMAL FIX: add fxRate, baseGrossTotalAmount, rateAt to InvoicePostingTotalsDto (sourced from SalesInvoiceServiceImpl.finalise lines 233-235); in ArSalePostedHandler stamp inv.setFxRate / setBaseOriginalAmount / setBaseOutstandingAmount / setRateAt on the created open item. For the residual-receivable case (outstandingAmount < gross) derive base = receivable Ã— fxRate so the sub-ledger base equals the AR debit posted in the SALES journal. Add an IT: foreign credit sale â†’ finalise â†’ ArSalePostedHandler â†’ receipt + period-end revaluation, asserting the open item carries the invoice rate and AR control nets to zero across the lifecycle.

---

### 3. [BLOCKER] Sales-originated foreign AR open item is never stamped with fx_rate/base_original_amount, so receipt settlement relieves AR and computes realized FX at rate=1 instead of the invoice rate

CONFIRMED â€” genuine defect on a supported (multi-currency) path. Verified end-to-end on feat/fx-multicurrency.

PRODUCER side (AR control debited in BASE at invoice rate): SalesPostingHandler.postSalesEntry (SalesPostingHandler.java:126-131) passes totals.currency()=USD and totals.grossTotalAmount()=face to GLPostingSafeInvoker.postSaleInNewTx, which converts faceâ†’base (GLPostingSafeInvoker.java:96-118: baseNet/baseVat = fxConverter.toBase(...); baseGross = balancingPlug(...) = faceÃ—rate) and debits ACCOUNTS_RECEIVABLE at that base value (line 118). For USD 1,000 @ 2,500 the AR control DR = TZS 2,500,000.

OPEN-ITEM side (never stamped): ArSalePostedHandler.createOpenItemIfCredit (ArSalePostedHandler.java:159-164) builds `new ArInvoice(... receivable, totals.currency() ...)` and saves it (line 164) WITHOUT calling setFxRate/setBaseOriginalAmount/setBaseOutstandingAmount/setRateAt. The source DTO it re-reads (InvoicePostingTotalsDto.java:21-43; produced by SalesInvoiceServiceImpl.findPostingTotalsByUidAndCompany, lines 660-676) carries `currency` but NO fxRate and NO base amount, so the handler has nothing to stamp. ArInvoice.java:95 defaults fxRate=BigDecimal.ONE; baseOriginalAmount/baseOutstandingAmount stay NULL (entity has no @PrePersist deriving them). Migration V78 (V78__fx_document_base_triple.sql:45-47) sets ar_invoices.fx_rate NOT NULL DEFAULT 1 with nullable base columns; its backfill (lines 52-54) only touches pre-existing rows, not new SALE inserts.

The commit c89d5d3 ("wire FX rate-triple stamping into SalesInvoiceServiceImpl.finalise") stamps the SalesInvoice entity (SalesInvoiceServiceImpl.java:233-235), NOT the ArInvoice open item â€” a different aggregate. A repo-wide grep confirms the ONLY callers of setFxRate+setBaseOriginalAmount on an ArInvoice are TEST files (FxRevaluationRunServiceIT). No production path stamps the SALE-originated AR open item.

CONSUMER side (relief + realized FX computed at rate=1): ArReceiptServiceImpl.recordAndAllocate line 199 reads invoiceRate = inv.getFxRate() != null ? inv.getFxRate() : ONE â†’ returns the default 1; line 200-201 baseRelieved = allocatedAmount Ã— 1 = face; line 215-216 decrements baseOutstanding from the fallback originalAmount; line 289 fxDelta = sumBaseRelieved âˆ’ sumBaseSettled; line 324 arCrBase = sumBaseRelieved + baseUnallocated. For a full USD 1,000 receipt at 2,600: sumBaseRelieved = 1,000 (wrong; should be 2,500,000), sumBaseSettled = 2,600,000, fxDelta = âˆ’2,599,000 â†’ fictitious TZS 2,599,000 FX GAIN (real gain = 100,000), and AR control CR = 1,000, leaving TZS 2,499,000 of the original 2,500,000 AR DR permanently dangling in GL 1200 while the sub-ledger marks the invoice PAID.

INVARIANTS: I-3 is violated â€” the relieved open-item value is NOT read at the original invoice rate; it is read at the default rate of 1, producing an economically wrong REALIZED_FX amount AND a non-clearing AR control balance (sub-ledger/GL divergence). I-1 still holds (the receipt journal balances in base by construction: Cash DR 2,600,000 = AR CR 1,000 + FX gain CR 2,599,000), which is exactly why all 820 tests are green. The IT FxArReceiptSettlementIT (lines 162-172, 278-284) masks the defect: it patches fx_rate/base_original_amount/base_outstanding_amount via raw jdbc.update with the explicit comment "bypassed here to simulate what T2 would stamp on a real foreign sale invoice" â€” nothing in production stamps it. The opening-balance path compounds it: ArOpeningBalanceServiceImpl.setOpeningBalance (lines 91-104) posts the GL journal in the FACE currency (no toBase conversion) and creates the ArInvoice without stamping FX, so foreign OB rows also default fx_rate=1 / base NULL.

MINIMAL FIX: extend InvoicePostingTotalsDto with fxRate + baseGrossTotalAmount (already stamped on SalesInvoice at finalise, SalesInvoiceServiceImpl.java:233-234) and populate them in findPostingTotalsByUidAndCompany (read inv.getFxRate()/inv.getBaseGrossTotalAmount()); then in ArSalePostedHandler.createOpenItemIfCredit (after line 164) call inv.setFxRate(totals.fxRate()), inv.setBaseOriginalAmount(totals.baseGrossTotalAmount()), inv.setBaseOutstandingAmount(totals.baseGrossTotalAmount()), inv.setRateAt(...). This must match exactly the rate/base used by GLPostingSafeInvoker for the AR debit so relief clears 1200 to zero. Separately stamp the FX triple (and post in base) in ArOpeningBalanceServiceImpl. Add an IT that drives the REAL salesâ†’ARâ†’receipt path (no raw SQL) and asserts AR control nets to zero and realized FX == (settlementRateâˆ’invoiceRate)Ã—face.

---

### 4. [HIGH] AP paymentRun applies the first bill's settlement rate to every bill with no same-currency guard, corrupting base cash value and realized FX on mixed-currency runs

CONFIRMED â€” genuine defect on a supported (multi-currency) path. Verified on feat/fx-multicurrency.

EVIDENCE (ApPaymentServiceImpl.paymentRun):
- L233 `String currency = openBills.get(0).getCurrency();` â€” currency taken from the FIRST selected bill only.
- L235-238 resolves ONE `settlementRate` (and `baseScale`) for that first-bill currency.
- L262-263 `baseRelieved = toAllocate.multiply(bill.getFxRate())` â€” correctly per-bill (relief at each bill's original invoice rate).
- L265-266 `baseSettled = toAllocate.multiply(settlementRate)` â€” applies the FIRST bill's currency settlement rate to EVERY bill's face, ignoring bill.getCurrency(). For a non-first-currency bill this multiplies that bill's face by the wrong currency's rate.
- L257-281 accumulate sumBaseSettled from these figures; postPaymentToGl L400 `fxDelta = sumBaseSettled.subtract(sumBaseRelieved)` and L428 `cashCrBase` are therefore corrupt; cashTxnRecorder.recordSettlement (L455-460) also records the whole batch under the first bill's currency.

REACHABILITY: SupplierBillRepository.findOpenForPayment (L30-42), findOpenForPaymentAllSuppliers (L44-54), and findOpenByUids (L56-65) all lack any currency filter, so a single supplier/company can yield a heterogeneous-currency open-bill list on every selection path. SupplierBill.currency is a real per-bill non-null column (entity L73-74); foreign AP bills are first-class (EnterBillRequest.currency; BillMatchServiceImpl stamps fx_rate at L427; dedicated findOpenForeignForRevaluation query). Multi-currency AP is supported, and no same-currency guard exists anywhere in paymentRun (grep confirms only the identity-path comments).

INVARIANTS: I-1 is NOT broken â€” the journal balances by construction (DR AP sumBaseRelieved + FX plug fxDelta = CR Cash sumBaseSettled), which is exactly why all 820 green tests pass. I-3 IS broken on mixed-currency runs: cash is valued at the wrong settlement rate for non-first-currency bills, so base cash value, the realized-FX plug, and the recorded cash-transaction currency are all economically wrong. The finding's own concession (balances yet wrong) is accurate.

TEST GAP: ApPaymentServiceIT.paymentRun_settlesAllDueBills uses only TZS (base); FxApPaymentSettlementIT exercises foreign currency only via paySingle (single bill, where first-bill currency is trivially the only currency). No green test ever runs a foreign or mixed-currency paymentRun. paySingle is correctly immune; the defect is isolated to paymentRun.

MINIMAL FIX (option a, matches the single-currency ApPayment header model): after selecting openBills and before deriving currency, reject heterogeneous runs â€” e.g. `if (openBills.stream().map(SupplierBill::getCurrency).distinct().count() > 1) throw new IllegalStateException("Payment run bills must share one currency");`. (Option b â€” resolving a settlement rate per distinct currency and computing baseSettled/baseScale per bill â€” would also work but conflicts with the single-currency payment header and is larger.)

---

### 5. [BLOCKER] AP (and mixed AR+AP) FX revaluation builds an UNBALANCED base journal; failure is silently swallowed to null

CONFIRMED â€” genuine BLOCKER on a supported path. Multi-currency AP revaluation is a first-class feature (ADR-0036 D-6): SupplierBillRepository.findOpenForeignForRevaluation (lines 109-117) selects open foreign AP bills, and FxRevaluationRunServiceImpl.computeRevalLines builds an "AP" RevalLine (lines 450-451). The signs collide exactly as the finding states.

Root cause: in computeRevalLines, AP adjustment sign is INVERTED vs AR (line 447: adjustment = carryingBase - revaluedBase, opposite to AR line 402). So an AP adjustment>0 means "gain" and <0 means "loss" from the company's view. In post() the accumulator keys purely on adjustment sign: adjustment>0 â†’ totalGain (lines 217-218), else â†’ totalLoss (lines 219-220). The aggregate FX balancing leg in postRevaluationJournal keys ONLY off totalGain/totalLoss with a fixed side: gain â†’ CR UNREALIZED_FX_GAIN (line 529), loss â†’ DR UNREALIZED_FX_LOSS (line 533). But the per-line AP control leg is placed on the SAME side as that aggregate leg:
- AP gain (adjustment>0): control leg CR AP for absAdj (line 495) AND aggregate CR FX_GAIN for totalGain (line 529) â†’ two credits, no offsetting debit.
- AP loss (adjustment<0): control leg DR AP for absAdj (line 506) AND aggregate DR FX_LOSS for totalLoss (line 533) â†’ two debits.

Worked example (verified): AP-only loss, carrying=1000, revalued=1200 â†’ adjustment=-200 â†’ DR AP 200 (line 506) + DR FX_LOSS 200 (line 533) = Î£dr 400, Î£cr 0. AP-only gain, carrying=1200, revalued=1000 â†’ adjustment=+200 â†’ CR AP 200 (line 495) + CR FX_GAIN 200 (line 529) = Î£cr 400, Î£dr 0. Mixed AR gain 200 + AP gain 100 â†’ DR AR 200, CR AP 100, CR FX_GAIN 300 â†’ Î£dr 200 â‰  Î£cr 400. AR-only is fine (AR gain: DR AR + CR FX_GAIN; AR loss: CR AR + DR FX_LOSS) which is why the green suite passes. LineDraft positions confirmed: record LineDraft(accountId, debitAmount, creditAmount, ...) (JournalEntryDraft.java lines 32-37); 5-arg ctor at 52-56.

This breaks I-1: GLPostingServiceImpl.post enforces Î£debit==Î£credit and throws IllegalArgumentException on imbalance (lines 127-132). The draft is posted via glSafeInvoker.postInNewTx, which catches ALL exceptions and returns null (GLPostingSafeInvoker.java lines 173-182), so the imbalance NEVER fails loud. In post() the null glEntry skips the if(glEntry != null) block (line 238): run stays PREVIEWED with glEntryUid=null, yet the method still saves the run (line 259), fires the outbox FX_REVALUATION_EXECUTED event (262-271) and audit (273-280), and returns a DTO â€” so the caller sees an apparently successful run while NO AP revaluation journal posts. That defeats I-4 for AP. Beyond imbalance, the AP control leg is also accounting-wrong directionally: an AP gain should DR (reduce) the payable, but line 495 CRs it.

Test gap confirmed: FxRevaluationRunServiceIT exercises only a single-currency AR gain (Test 2 asserts totalGain>0, totalLoss==0, one line, sourceType "AR"); no AP, loss, or mixed scenario â€” so 820 green tests never touch this path.

Minimal correct fix: emit the FX leg per line as the opposite-side complement of each line's control leg, computed by (sourceType, sign) rather than from aggregate totals â€” AR gain: DR AR / CR FX_GAIN; AR loss: CR AR / DR FX_LOSS; AP gain: DR AP / CR FX_GAIN; AP loss: CR AP / DR FX_LOSS. Drop the aggregate balancing legs (lines 527-535) in favour of per-line complements (or net per side after correcting AP control sign), and assert Î£dr==Î£cr in postRevaluationJournal before calling postInNewTx so any residual imbalance fails loud instead of being swallowed to null.

---

### 6. [HIGH] PREVIEWED idempotency fall-through re-inserts a duplicate (company,period) run, making a GL-failed period permanently un-repostable

CONFIRMED on feat/fx-multicurrency. Evidence: (1) FxRevaluationRunServiceImpl.post saves the run header in PREVIEWED state at line 202 BEFORE GL posting. postRevaluationJournal (lines 469-549) returns null on real supported failures â€” missing UNREALIZED_FX_GAIN/LOSS gl_config (line 522) or glSafeInvoker.postInNewTx swallowing a closed-period/missing-config exception (GLPostingSafeInvoker.postInNewTx lines 172-182, REQUIRES_NEW so the outer TX is NOT poisoned and returns null). With glEntry==null the if at line 238 is skipped, status stays PREVIEWED, and line 259 runs.save(run) commits the orphan PREVIEWED row (no exception thrown anywhere -> the class-level @Transactional commits; outbox at 262 and audit at 273 tolerate null gl_entry_uid). (2) On retry, lines 180-181 find the existing PREVIEWED run, but the guard at line 184 (ex.getStatus() != PREVIEWED) is FALSE, so it does NOT early-return; execution falls through to lines 199-202 and calls runs.save(new FxRevaluationRun(..., period.getId(), ...)) â€” a second row with the same (company_id, fiscal_period_id). (3) uq_fx_revaluation_run_company_period UNIQUE (company_id, fiscal_period_id) (V80 line 42) -> DataIntegrityViolationException on commit. No path reuses/updates/deletes the stuck row; reverse() throws IllegalStateException for PREVIEWED runs (lines 293-295). The period is permanently un-repostable. (4) The cited precedent DepreciationRunServiceImpl.post (lines 182-188) posts GL FIRST then saves the run LAST, so a GL failure rolls back the whole TX and leaves no orphan row; the FX impl inverted that order and added a never-completing PREVIEWED fall-through. (5) The 820-green suite misses it: there is no *FxRevaluation*Test* file and no test exercises the PREVIEWED-retry / postInNewTx-returns-null path. Sacred invariants: none of I-1..I-6 is breached (no base imbalance, no rate mutation, no silent rate=1 default) â€” this is a D-6 idempotency/availability defect, matching the finding's own 'none (quality)' classification. Minimal correct fix: when the guard finds an existing PREVIEWED run, REUSE it â€” delete its existing run-lines, reset totals/glEntryUid, retry the GL post, and save the SAME row in place (or delete the stale PREVIEWED run + lines before inserting); never insert a second (company, period) row.

---

### 7. [BLOCKER] Foreign credit-sale AR open item created without the stamped FX triple (fx_rate defaults to 1, base amounts NULL) â€” breaks realized FX (I-3) and unrealized reval (I-4)

CONFIRMED. Verified end-to-end on feat/fx-multicurrency; foreign-currency credit sales are a supported path and the FX triple is never propagated to the AR open item.

EVIDENCE (every link cited):
1. Foreign sales are supported and the SalesInvoice IS stamped. SalesInvoiceServiceImpl.finalise lines 231-235: fxConv = fxConverter.toBase(gross, inv.getCurrency(), companyId, today); inv.setFxRate(fxConv.rate()); inv.setBaseGrossTotalAmount(fxConv.baseAmount()); inv.setRateAt(...). CurrencyConversionServiceImpl.convert lines 66-73 returns the real foreign rate (e.g. USDâ†’TZS rate=2500, base=faceÃ—2500). So a USD invoice ends up with fx_rate=2500, base_gross=faceÃ—2500 on sales_invoices.

2. The triple is NOT carried to AR. InvoicePostingTotalsDto (record, lines 21-43) has NO fxRate / baseGrossTotalAmount / rateAt fields. SalesInvoiceServiceImpl.findPostingTotalsByUidAndCompany lines 660-675 constructs the DTO from face amounts + currency only; it never reads inv.getFxRate()/getBaseGrossTotalAmount().

3. The AR open item is created WITHOUT stamping. ArSalePostedHandler.createOpenItemIfCredit lines 159-164: new ArInvoice(companyId, branchId, customerId, SALE, uid, null, receivable, totals.currency(), invoiceDate, dueDate, null) â€” face only. No setFxRate/setBaseOriginalAmount/setBaseOutstandingAmount/setRateAt is called anywhere in this class (grep over src/main/java confirms the only ArInvoice setters of the triple do not exist on this path). ArInvoice.java line 95 defaults fxRate=BigDecimal.ONE; baseOriginalAmount/baseOutstandingAmount default NULL (lines 98-105). The V78 column DEFAULT for fx_rate is 1; base_original/base_outstanding are nullable with only a one-time back-fill UPDATE...WHERE NULL (V78 lines 44-54) â€” no insert trigger â€” so a NEW foreign row persists fx_rate=1, base=NULL.

4. Proof of asymmetry / that this is a defect, not by-design: the analogous AP open-item path DOES stamp correctly â€” BillMatchServiceImpl lines 425-428 call setBaseGrossAmount/setBaseOutstandingAmount/setFxRate/setRateAt. AR omits the identical step. (ArOpeningBalanceServiceImpl line 100 is a second, secondary instance of the same omission.)

DOWNSTREAM DAMAGE (confirmed in code):
- I-3 VIOLATED (realized FX). ArReceiptServiceImpl.recordAndAllocate line 199: invoiceRate = inv.getFxRate() != null ? inv.getFxRate() : ONE â†’ reads 1, not 2500. Line 200-201 baseRelieved = allocatedFaceÃ—1; line 202-203 baseSettledSlice = allocatedFaceÃ—settlementRate(2500). FX plug fxDelta = sumBaseRelieved âˆ’ sumBaseSettled (line 289) = faceÃ—1 âˆ’ faceÃ—2500 â†’ fabricated multi-million gain/loss. AR control CR = sumBaseRelieved+baseUnallocated (line 324) relieves at faceÃ—1 while GL originally debited AR at base_gross=faceÃ—2500, so the AR control sub-ledger cannot reconcile to base. (The journal still nets to zero in base â€” I-1 holds by construction â€” but the realized-FX number is economically wrong, exactly the I-3 violation.)
- I-4 VIOLATED (unrealized reval). The foreign open item IS selected by ArInvoiceRepository.findOpenForeignForRevaluation (currency<>base). FxRevaluationRunServiceImpl.computeRevalLines lines 386-389: baseOut = getBaseOutstandingAmount() (NULL) ? : getOutstandingAmount() â†’ carryingBase = foreign face. Line 400-402: adjustment = faceÃ—spot âˆ’ face â‰ˆ the entire base value as bogus unrealized gain.
- I-2 purpose defeated: the immutable triple is stamped on sales_invoices but is write-only on the AR side.

Green tests miss it because the reval/receipt ITs set baseOriginalAmount manually rather than driving the real ArSalePostedHandler creation path (as the finding states).

MINIMAL CORRECT FIX:
1. Add fxRate, baseGrossTotalAmount, rateAt to InvoicePostingTotalsDto and populate them in findPostingTotalsByUidAndCompany from inv.getFxRate()/getBaseGrossTotalAmount()/getRateAt().
2. In ArSalePostedHandler.createOpenItemIfCredit, after constructing the ArInvoice, stamp: inv.setFxRate(totals.fxRate()); inv.setRateAt(totals.rateAt()); and set baseOriginalAmount = baseOutstandingAmount = round(receivable Ã— totals.fxRate()) at base minor-units HALF_UP (use the relieved-equivalent of the open-item face, since base_gross on the invoice is for the full gross while the open item may be a partial residual). Mirror BillMatchServiceImpl lines 425-428.
3. Apply the same stamping to ArOpeningBalanceServiceImpl line 100.
4. Add a fail-loud invariant so a foreign (currency != base) ar_invoices row can never persist with fx_rate=1 / base NULL.

---

### 8. [HIGH] Foreign credit-sale AR open item is never stamped with the invoice FX rate/base (ArSalePostedHandler) â†’ wrong realized FX (I-3) and AR sub-ledger vs GL-control base mismatch; the headline "GL poster mutates/drifts the immutable triple (I-2)" is REFUTED as an invariant violation

PARTIALLY CONFIRMED â€” the headline (I-2) framing is REFUTED, but the finding's "dimension" excavation lands a REAL, HIGH-severity defect on a supported path that breaks I-3.

WHAT IS REFUTED (the I-2 headline):
- "Poster ignores the stamped triple and re-derives base independently" is factually TRUE: InvoicePostingTotalsDto (lines 21-43) carries no fx_rate/base; GLPostingSafeInvoker.postSaleInNewTx recomputes baseNet=toBase(net,...) (line 96), baseVat=toBase(vat,...) (line 102), baseGross=balancingPlug([-baseNet,-baseVat]) (lines 106-108) via a fresh fxConverter.toBase at postingDate. SalesInvoiceServiceImpl.finalise stamps base_gross=round(grossÃ—rate) once at LocalDate.now() (lines 231-234).
- But this does NOT violate I-2: the stamped triple is never re-written by any post-finalise path (the finding itself concedes "naive immutability holds"). And it does NOT violate I-1: the poster's plug (FxDocumentConverter.balancingPlug, lines 66-71) makes Î£base==0 exactly by construction; SalesInvoiceFxPostingIT.foreignUsdInvoice_... asserts Î£debit==Î£credit (lines 275-277). The claimed "1-minor-unit drift" between stamped round(grossÃ—rate) and posted round(netÃ—rate)+round(vatÃ—rate) is real arithmetic but cosmetic: base_gross_total_amount has NO downstream consumer (Grep for getBaseGrossTotalAmount finds only tests). The load-bearing part of the triple is fx_rate, which IS read downstream â€” and in normal operation the poster resolves the same effective row, so rate matches. The back-dated-rate window is real (FxRateServiceImpl.addRate, lines 75-121, never calls the existsBy duplicate-guard; finalise uses LocalDate.now() while SalesPostingHandler line 115-117 uses finalisedAt-as-UTC) but narrow and still I-1-safe. So as an I-2 immutability defect: REFUTED.

WHAT IS CONFIRMED (the real defect, breaks I-3): For a FOREIGN CREDIT sale, the AR open item is created WITHOUT the FX triple. ArSalePostedHandler.createOpenItemIfCredit (ArSalePostedHandler.java:159-164) constructs `new ArInvoice(companyId, branchId, customerId, SALE, uid, null, receivable, totals.currency(), invoiceDate, dueDate, null)` and saves it â€” never calling setFxRate/setBaseOriginalAmount/setBaseOutstandingAmount/setRateAt. The DTO it re-reads (InvoicePostingTotalsDto) carries no rate to stamp anyway. The ArInvoice ctor (ArInvoice.java:116-132) leaves fxRate=BigDecimal.ONE (field default, line 95), baseOriginalAmount=null (line 100), baseOutstandingAmount=null (line 105). Consequences on settlement (ArReceiptServiceImpl.recordAndAllocate): line 199 invoiceRate = inv.getFxRate()!=null?...:ONE â†’ reads the un-stamped 1; line 200-201 baseRelieved = allocatedAmount Ã— 1 = FACE, not original base; line 212-214 baseOutstanding falls back to originalAmount (face). So sumBaseRelieved is the FACE sum, the realized-FX plug = Î£base_relieved âˆ’ Î£base_settled is computed as if the invoice were booked at rate 1 â†’ economically WRONG REALIZED FX (I-3), and the AR control CR (faceÃ—1) does not match the GL AR debit the poster booked at faceÃ—real_rate (sub-ledger vs GL-control divergence). Unrealized revaluation (I-4) reading base_outstanding=null/face is likewise wrong.

WHY GREEN TESTS MISS IT: SalesInvoiceFxPostingIT only finalises a CASH_WALK_IN customer (line 171), so the AR-open-item path is never hit for a foreign sale. FxArReceiptSettlementIT manufactures the missing state itself: it creates a TZS opening-balance invoice then raw-SQL sets fx_rate=2500, base_original_amount=2500000 (lines 162-172), with the comment "bypassed here to simulate what T2 would stamp on a real foreign sale invoice" â€” i.e. it ASSUMES the sales path stamps the triple, which it does not. ArSalePostedHandlerIT exercises a CREDIT_ACCOUNT customer but only in TZS (finaliseInvoiceFor uses "TZS", line 267), where fxRate=1 is coincidentally correct, so no assertion catches the foreign gap.

MINIMAL CORRECT FIX: (1) Add fxRate, baseOriginalAmount (and rateAt) to InvoicePostingTotalsDto and populate them in findPostingTotalsByUidAndCompany from the stamped invoice (the existing sales_invoices.fx_rate / base_gross_total_amount / rate_at). (2) In ArSalePostedHandler.createOpenItemIfCredit, after constructing the ArInvoice, stamp inv.setFxRate(totals.fxRate()), inv.setBaseOriginalAmount(baseReceivable) and inv.setBaseOutstandingAmount(baseReceivable) and inv.setRateAt(totals.rateAt()), deriving baseReceivable from the stamped rate (round(receivableÃ—rate)). This makes the AR sub-ledger base value reconcile with the GL AR-control debit and makes realized FX (I-3) and revaluation (I-4) economically correct. Separately (lower priority, the I-2 headline), threading the stamped rate into the GL poster as the finding suggests would also close the back-dated-rate window and the cosmetic base_gross drift, though neither currently breaks I-1.

---

### 9. [HIGH] Foreign AR/AP settlement leaves an unclearable control-account residual; the SALE-source AR open item is never FX-stamped (fx_rate defaults to 1), corrupting the AR control account and realized/unrealized FX on every foreign credit sale

CONFIRMED on supported multi-currency paths. Two distinct, real defects, both invisible to the green suite.

(A) AP rounding-drift residual (the headline claim) â€” REAL, small magnitude. BillMatchServiceImpl.postMatchedBillToGl posts CR AP as a per-leg-rounded plug: baseGoodsNet/baseServiceTotal accumulate already-rounded fxConverter.toBase(lineNet).baseAmount() per line (lines 356-371), baseVat is rounded independently (lines 382-389), and baseAp = balancingPlug(negated DR legs) (lines 394-404). But the triple is stamped base_gross_amount = grossConv.baseAmount() = a SINGLE round(gross Ã— rate) (lines 330-331, 425-426). CurrencyConversionServiceImpl.toBase rounds HALF_UP on every call (lines 70-71), so for a multi-line foreign bill baseAp â‰  stamped base by up to (#legsâˆ’1) minor units (verified numerically with rate 2500.005, 3 lines + VAT: per-leg sum 885,003 vs stamped 885,002). At settlement ApPaymentServiceImpl.paySingle computes billRate = bill.getFxRate() = grossConv.rate() (line 163) and baseRelieved = round(toAllocate Ã— billRate) (lines 164-165); at full pay toAllocate = gross face, so DR AP = sumBaseRelieved = round(gross Ã— rate) = the STAMPED base (postPaymentToGl lines 406-408), NOT the originally-posted baseAp. So after a fully-PAID bill the AP control account retains a residual = baseAp âˆ’ stamped that no journal clears (ApReconciliationQuery is read-only; no true-up exists). I-1 is NOT violated (each journal self-balances via its own plug/fxDelta); the broken property is the implicit "control account nets to zero on a fully-settled doc", and the realized-FX plug (fxDelta = baseSettled âˆ’ baseRelieved, line 400) is computed off the stamped value, so it does not absorb the residual. The same 2-leg pattern exists on AR: SalesInvoiceServiceImpl.finalise stamps base_gross_total = round(gross Ã— rate) (line 234) while GLPostingSafeInvoker.postSaleInNewTx posts DR AR = balancingPlug(baseNet, baseVat) (lines 96-108) â€” max 1-unit drift.

(B) The far more serious defect, which the finding correctly flags in its dimension note: the SALE-source AR open item is NEVER FX-stamped. ArSalePostedHandler.createOpenItemIfCredit builds the ArInvoice via the constructor with only face receivable + currency (lines 159-164) and never calls setFxRate/setBaseOriginalAmount/setBaseOutstandingAmount/setRateAt; ArInvoice defaults fx_rate = BigDecimal.ONE and leaves base_* null (entity lines 93-110). Meanwhile SalesPostingHandler â†’ GLPostingSafeInvoker.postSaleInNewTx debits AR 1200 in BASE (â‰ˆ2,500,000 TZS for USD 1000 @ 2500). At settlement ArReceiptServiceImpl reads invoiceRate = inv.getFxRate() = 1 (line 199) â†’ baseRelieved = round(face Ã— 1) = face, so CR AR â‰ˆ 1,000 TZS. Net: ~2,499,000 TZS orphaned in the AR control account per foreign credit sale, and realized FX (I-3) computed against rate 1 instead of the true invoice rate â€” economically catastrophic, not a rounding nit. I-4 is also broken: FxRevaluationRunServiceImpl falls back to outstanding_amount (face) when base_outstanding_amount is null (lines 386-388), revaluing the open item against a carrying base of 1,000 instead of 2,500,000.

Why the green suite misses both: FxArReceiptSettlementIT and FxApPaymentSettlementIT create the open item via the OPENING_BALANCE path and then hand-patch fx_rate/base_original_amount/base_outstanding_amount with raw SQL (FxArReceiptSettlementIT lines 166-172, whose own comment says "simulate what T2 would stamp on a real foreign sale invoice"); SalesInvoiceFxPostingIT uses CustomerKind.CASH_WALK_IN (line 171), and cash sales are skipped by ArSalePostedHandler (lines 127-131). So no test ever drives a foreign CREDIT sale through ArSalePostedHandler, nor a foreign multi-line supplier bill through runMatch â†’ settlement.

Minimal correct fix:
1. (Defect B, primary) In ArSalePostedHandler.createOpenItemIfCredit, after re-reading totals, convert the foreign receivable to base via FxDocumentConverter.toBase(receivable, totals.currency(), companyId, invoiceDate) and stamp inv.setFxRate(conv.rate()), inv.setBaseOriginalAmount(conv.baseAmount()), inv.setBaseOutstandingAmount(conv.baseAmount()), inv.setRateAt(conv.rateAt()) before save â€” using the SAME rate the sales invoice stamped at finalise (read sales_invoices.fx_rate / base_gross_total_amount, which finalise already froze, rather than re-looking-up) so the AR open-item base equals the DR AR base the SalesPostingHandler posted. Also stamp the OPENING_BALANCE AR path in ArOpeningBalanceServiceImpl.
2. (Defect A) Make the stamped base equal the EXACT posted control-leg base, not an independent single rounding: in BillMatchServiceImpl set bill.setBaseGrossAmount(baseAp.negate()) / setBaseOutstandingAmount(baseAp.negate()) (the sum of the per-line base legs actually posted), and mirror on the AR finalise side by stamping base_gross_total = baseNet + baseVat (the GLPostingSafeInvoker plug). Then full settlement relieves exactly what was booked and the control account squares to zero.

---

### 10. [HIGH] AR write-off posts the foreign FACE amount as base on both legs, leaving a permanent FX residual in AR control and misstating Bad Debt Expense

CONFIRMED on the supported foreign-AR path; one secondary sub-claim and the suggested fix are wrong.

EVIDENCE (ArWriteOffServiceImpl, branch feat/fx-multicurrency):
- L79 `writeOffAmount = inv.getOutstandingAmount()` is the FACE outstanding. ArInvoice.outstandingAmount is in the document currency (entity L53-58, `currency` column), so for a foreign invoice this is a foreign-face number.
- L85-87 resolve `currency` to the company base currency.
- L102-107 build BOTH LineDrafts (DR BAD_DEBT_EXPENSE / CR ACCOUNTS_RECEIVABLE) using that same face `writeOffAmount`, labelled with base `currency`, and never call FxDocumentConverter/toBase. GLPostingServiceImpl.validateLine (L377-382) only checks the currency TAG == base and trusts the amounts are already base, so it cannot detect the face-as-base mislabel. Both legs being equal, the Î£-check (L120-132) passes, so I-1 is technically satisfied and the green tests are happy.
- L112 zeros face `outstandingAmount` only; `baseOutstandingAmount` is never decremented (no setter call anywhere in the class).

WHY IT'S A REAL DEFECT (breaks I-3): a foreign credit sale debits AR control (GL 1200) at BASE â€” GLPostingSafeInvoker.postSaleInNewTx L106-118 computes `baseGross = baseNet + baseVat` (face Ã— rate) and DRs AR with that base number. The write-off then CRedits AR with only the face number. For invoiceRateâ‰ 1 this leaves a permanent, unclearable residual of faceÃ—(rateâˆ’1) in AR control, and Bad Debt Expense is understated by the same FX difference. The write-off path has no currency guard (WriteOffRequest has no currency field; writeOff() never restricts currency), and ArWriteOffServiceIT has zero foreign/fxRate coverage â€” hence the green suite missed it. For a base-currency (TZS) invoice face==base, rate=1, so the journal is byte-identical and correct (I-5 holds) â€” exactly why it slipped past.

CORRECTIONS TO THE FINDING:
1) The sub-claim "inv.baseOutstandingAmount â€¦ carries base exposure into FxRevaluationRunServiceImpl" is REFUTED: ArInvoiceRepository.findOpenForeignForRevaluation (L107-115) filters `status IN ('OPEN','PARTIAL')`, and writeOff sets status=WRITTEN_OFF (L113), so the item is excluded from revaluation. The stale baseOutstandingAmount does not leak into I-4.
2) The suggested fix `baseRelieved = writeOffAmount Ã— inv.getFxRate()` is itself broken on this branch: the AR open item's FX triple is NEVER stamped in production. The only SALE-origin creator, ArSalePostedHandler.createOpenItemIfCredit (L159-164), builds ArInvoice with face amount + totals.currency() and never sets fxRate/baseOriginalAmount/baseOutstandingAmount; InvoicePostingTotalsDto (the handler's input) doesn't even carry them; there is no @PrePersist. So inv.getFxRate() defaults to BigDecimal.ONE (entity L95) and the base columns insert NULL. Multiplying by getFxRate()=1 reproduces the face number. (This is a deeper upstream gap that also makes the supposedly FX-aware ArReceiptServiceImpl relieve a foreign SALE-origin invoice at face â€” `baseRelieved = allocated Ã— invoiceRate(=1)`, L199-201 â€” but that is out of scope for THIS finding.) A write-off also has no realized FX leg by nature (nothing settled at a different rate); routing a difference to REALIZED_FX_GAIN/LOSS as suggested is conceptually wrong.

MINIMAL CORRECT FIX: relieve AR and debit Bad Debt at the open item's ORIGINAL carrying base value, not the face. Use `baseRelieved = inv.getBaseOutstandingAmount()` when present (else fall back to outstandingAmount for legacy rate=1 rows), post both legs with `baseRelieved` in base currency, and set `inv.setBaseOutstandingAmount(ZERO)` alongside zeroing outstandingAmount. This keeps the entry balanced, clears the sub-ledger fully, and is byte-identical for base-currency invoices (baseRelieved==face). For this to actually work for FOREIGN sales, the prerequisite upstream fix is to stamp the AR open item's FX triple at creation (carry fxRate/baseOriginalAmount/baseOutstandingAmount from the finalised SalesInvoice â€” SalesInvoiceServiceImpl L233-235 already computes them â€” through InvoicePostingTotalsDto into ArSalePostedHandler).

---

### 11. [HIGH] AR credit note ignores document currency: posts foreign-face amounts as base and relieves foreign open item with a base-intended figure (no FX conversion, no realized-FX, base_outstanding not adjusted)

CONFIRMED â€” genuine defect on a supported (multi-currency) path; the green suite has no foreign credit-note test (verified: no test under src/test combines CreditNote with USD/EUR/fxRate/foreign/currency).

Mechanical claims all verified in ArCreditNoteServiceImpl.raise:
- L87-89: `currency` is set from `company.getBaseCurrency()`; `req.currency()` is NEVER read. The service has ZERO FX logic (grep for CurrencyConversion/toBase/getFxRate/baseOutstanding/REALIZED_FX returns nothing).
- L91-93: netAmount/vatAmount come straight from the request; totalAmount = net+vat.
- L116-124: those request amounts are posted as base-currency LineDrafts (currency = base) with no toBase conversion.
- L102 + L143-144: totalAmount (a base-intended figure) is compared against, and subtracted from, targetInvoice.getOutstandingAmount(), which is the FOREIGN face (ArInvoice.java L53-58: outstanding_amount is in the immutable `currency` column). Units mismatch on a foreign invoice.
- baseOutstandingAmount is never touched, contradicting the entity contract (ArInvoice.java L102-105: "Decremented when receipts / CN / write-offs reduce it") and V78 L41/L47 ("base_outstanding_amount ... moves with outstanding").

Supported-path proof (not theoretical):
- V78 L44-54 adds the FX base-triple to ar_invoices specifically so AR open items can be foreign; ADR-0036 supports foreign sales docs.
- SalesReturnServiceImpl.raiseCreditNote L304-315 passes order.getCurrency() (SalesOrder.currency, L49-50, a free 3-char column â†’ can be foreign) AND foreign net/vat amounts into RaiseCreditNoteRequest; the GL-posting bug fires for this caller whenever the order is foreign (this caller passes arInvoiceUid=null, so the open-item subtraction does not fire from it, but the wrong-unit GL post does).
- The REST endpoint ArCreditNoteController.raise (POST /api/v1/ar/credit-notes) accepts arInvoiceUid, so a caller can target a foreign AR open item and trigger the L102/L143 units mismatch.
- The FX-aware reference path ArReceiptServiceImpl.recordAndAllocate proves the intended design: it reads req.currency() (L139), resolves a settlement rate via fxConversion.toBase (L145-147, fail-loud), relieves AR at the invoice's ORIGINAL rate inv.getFxRate() â†’ baseRelieved (L199-216), decrements BOTH outstandingAmount and baseOutstandingAmount (L210-216), and books a realized-FX plug (L287-320). The credit-note path does none of this.

Invariant impact: I-1 is NOT broken â€” the three legs share one currency and CR-AR (total) == DR-revenue(net)+DR-vat(vat), so the journal balances in base (this is why the finding's own note concedes I-1 passes). The VIOLATED invariant is I-3 (settlement/relief of a foreign open item must relieve at the invoice's original rate, decrement base, and post any realized FX to REALIZED_FX_GAIN/LOSS) and the no-conversion-before-LineDraft spirit of ADR-0036 D-3. I-5 (day-1 base-currency no-regression) is preserved: for a TZS base company, req amounts are already base, rate is implicitly 1, base==face, baseOutstanding tracking is a no-op, so the journal is byte-identical and the 789 pre-FX tests are unaffected â€” consistent with HIGH (silently wrong on foreign docs) not BLOCKER (no base-currency regression, no balance break).

Note (context, not the bug): AR open items are not FX-stamped on creation either â€” ArSalePostedHandler.handle (ArSalePostedHandler.java L159-163) builds ArInvoice with totals.currency() but never sets fxRate/baseOriginalAmount, and InvoicePostingTotalsDto (L21-43) carries no fxRate; ArOpeningBalanceServiceImpl.setOpeningBalance (L73, L91-94) has the identical no-conversion bug. This corroborates that the secondary AR paths were left FX-unaware, but the credit-note defect stands on its own.

Minimal correct fix: carry the document currency on the credit note (use req.currency(), default to base when blank); if arInvoiceUid is present, validate req.currency() equals targetInvoice.getCurrency() and compare/subtract in FACE units against outstandingAmount; convert net/vat to base via fxConversion.toBase at the appropriate rate before building LineDrafts; relieve the target at the invoice's original fxRate, decrement baseOutstandingAmount by the relieved base, and book the realized-FX delta (relieved-base âˆ’ credit-base) to REALIZED_FX_GAIN/LOSS; keep an identity short-circuit for base currency to preserve byte-identical day-1 behaviour.

---

### 12. [HIGH] V80 grants FX.REVALUE / FX.EXPOSURE.VIEW with WHERE r.name = 'ORG_ADMIN' (zero-row match) â€” the two FX revaluation permissions are seeded but never granted to ORG_ADMIN

CONFIRMED â€” genuine defect on a supported path.

Evidence chain, all verified in code on feat/fx-multicurrency:

1) Role identity. V1__baseline.sql seeds the ORG_ADMIN row as `INSERT INTO roles (uid, code, name, ...) VALUES ('0000...', 'ORG_ADMIN', 'Organisation Administrator', ...)` (lines 255-257). The roles table has distinct code (VARCHAR(40)) and name (VARCHAR(120)) columns (lines 199-200). So for that row code='ORG_ADMIN' but name='Organisation Administrator'.

2) The defect. V80__fx_revaluation_runs.sql line 117: the role_permission INSERT...SELECT uses `WHERE r.name = 'ORG_ADMIN'`. No roles row has name='ORG_ADMIN', so the predicate matches zero rows and the INSERT inserts nothing. Grep over the whole migration tree shows `name = 'ORG_ADMIN'` occurs ONLY at V80:117 and there is no UPDATE that ever sets roles.name to 'ORG_ADMIN' â€” so the name stays 'Organisation Administrator' permanently. Every other grant uses r.code (V1:264, V77:131 in this same feature). The two perms FX.REVALUE and FX.EXPOSURE.VIEW are themselves inserted correctly into permissions by V80:107-111; only the grant join is broken.

3) No alternate grant. FX.REVALUE / FX.EXPOSURE.VIEW are referenced only by V80 (perm seed + grant) and the controller (grep confirms). V77's catch-all `WHERE r.code='ORG_ADMIN' AND p.module='fx'` (V77:127-133) runs in V77, which executes BEFORE V80, so those two p.module='fx' rows do not yet exist when V77's grant runs. V80 is the last FX migration; nothing later re-grants. Net: both perms are orphaned (attached to no role).

4) Real impact. FxRevaluationRunController gates all five endpoints: @perm.has('FX.REVALUE') on /preview (line 51), POST post (67), /uid/{uid}/reverse (79); @perm.has('FX.EXPOSURE.VIEW') on GET list (91) and GET /uid/{uid} (105). PermissionChecks.has (line 28-30) delegates to PermissionResolver.hasPermission, whose non-root path returns resolve(...).contains(code) (PermissionResolver:77-78), and resolve reads role_permission via UserRoleRepository.resolvePermissionCodes. With no grant, a non-root ORG_ADMIN gets an empty/incomplete set and is denied (403) on the entire FX revaluation feature. It fails CLOSED (deny), so there is no security or data exposure.

5) Why the 820 green tests miss it. FxRevaluationRunServiceIT authenticates as root: root.setRoot(true) (line 106) and Principal(..., true, ...) (line 117). PermissionResolver.hasPermission short-circuits to true for principal.root() (lines 67-76) WITHOUT ever calling resolve(), so the role_permission grant is never exercised. The IT also calls the service directly, bypassing @PreAuthorize entirely. Hence the orphaned grant is invisible to the suite.

Invariant mapping: This touches I-6 only in its 'perm seeded AND granted' aspect â€” the perm is seeded but the per-tenant role grant never happens. It does NOT cause a silent default-to-1 rate, nor any tenant cross-leak (service-layer assertCanActIn is present on every FX read/write per the controller and is unaffected). It does NOT touch the GL/accounting invariants I-1..I-5 (no journal, balance, rate-triple, realized or unrealized FX logic is involved). So the accounting core is safe; the harm is purely that the FX revaluation feature is unreachable by its intended non-root admin role.

Minimal correct fix (as the finding proposes): in V80 line 117 change `WHERE r.name = 'ORG_ADMIN'` to `WHERE r.code = 'ORG_ADMIN'`. V80 is on the unreleased feature branch (V1-V79 frozen), so edit it in place; if it has already been applied anywhere, add V81 re-running the grant with r.code (the INSERT...SELECT ... ON CONFLICT DO NOTHING is idempotent). Recommend a non-root assertion test (ORG_ADMIN holds CURRENCY.VIEW/MANAGE, FX.REVALUE, FX.EXPOSURE.VIEW) so root-bypass can't mask this class of bug again.

Severity HIGH: an entire shipped feature surface (all FxRevaluationRunController endpoints) is inaccessible to the role that is supposed to own it; not BLOCKER because it fails closed (no corruption / no security breach) and the fix is a one-token migration change.

---

### 13. [HIGH] CurrencyController.addRate @perm.scoped passes the Long companyId where a company UID String is required â€” denies ALL non-root users holding CURRENCY.MANAGE (403 on POST /api/v1/fx/rates)

CONFIRMED â€” genuine availability defect on the supported multi-currency path; not an invariant breach.

Code evidence (all on feat/fx-multicurrency, the current checked-out branch):
- CurrencyController.addRate line 61: @PreAuthorize("@perm.scoped(#req.companyId(),'company','CURRENCY.MANAGE')") â€” passes #req.companyId().
- UpsertRateRequest line 17: `@NotNull Long companyId` â€” a numeric PK, NOT a uid.
- PermissionChecks.scoped lines 37-45: for a non-root principal it returns `principal.root() || scopeGuard.canActOn(principal, targetType, uid)` (line 44). Non-root therefore depends entirely on canActOn.
- ScopeGuard.canActOn lines 612-619: non-root path is `companyIdOf(targetType, uid).map(...).orElse(false)`.
- ScopeGuard.companyIdOf line 480: `case "company" -> companies.findByUid(uid)`.
- CompanyRepository line 10: `Optional<Company> findByUid(String uid)`; UidEntity line 28: uid is a length-26 ULID (Ulid.next(), lines 35-39). A Long's decimal string ("5") is a few chars and can never match a 26-char ULID, so findByUid returns Optional.empty() -> canActOn=false -> scoped()=false -> 403 for EVERY non-root caller, even one correctly holding CURRENCY.MANAGE in their own company.
- SpEL coercion is real, not a throw: SecurityConfig (@EnableMethodSecurity, line 30) defines no custom MethodSecurityExpressionHandler (Javadoc lines 19-21 confirm "needs no custom expression handler"), so the default DefaultConversionService coerces Long->String via toString(). The POST /api/v1/fx/rates path is `authenticated()` + @PreAuthorize-gated (SecurityConfig lines 48) with no permitAll bypass, so the broken gate is genuinely reached on the real HTTP path.

Established contract that this violates: every other @perm.scoped(...,'company',...) gate passes a UID String â€” ChartOfAccountController line 43 (#req.companyUid), BranchController lines 38 & 51 (#companyUid / #request.companyUid()). The 'company' target type in companyIdOf is uid-resolved by design.

Why the 820 green tests miss it: there is NO web/MockMvc test for addRate; FxRateServiceIT.addRate_persistsAndReturnsDto calls fxRateService.addRate(req) directly (line 94), bypassing the controller @PreAuthorize entirely, and the IT principal is root (root.setRoot(true) line 73; Principal root=true lines 76-77). Root short-circuits both hasPermission and scoped() before canActOn is ever evaluated, so the wrong-typed gate is never exercised.

Invariant impact: NOT a sacred-invariant breach. I-6 (tenant isolation) still holds because FxRateServiceImpl.addRate line 76 independently does the CORRECT scope check `scopeGuard.assertCanActIn(RequestContext.get(), req.companyId())` with the numeric id. So this is no leak â€” it is over-restrictive: the controller gate is both wrong-typed AND redundant, and it bricks the rate-maintenance endpoint for every non-root admin (the normal operating mode), justifying HIGH.

Minimal correct fix: change line 61 to `@PreAuthorize("@perm.has('CURRENCY.MANAGE')")` and rely on the existing service-layer assertCanActIn(req.companyId()) for scope (mirrors how the service already enforces it; no DTO change). Alternative: add a companyUid String to UpsertRateRequest and gate `@perm.scoped(#req.companyUid(),'company','CURRENCY.MANAGE')` to match the uid contract. Either way add a non-root web IT posting a rate for the caller's own company asserting 200/201 (not 403).

---

## MEDIUM/LOW (fix the cheap correctness ones; defer cosmetic)

- **[MEDIUM] Sales finalise stamps base gross as convert(gross) but the posted journal uses convert(net)+convert(vat) â€” 1-minor-unit divergence between stamped triple and posted AR/GL base**
  - file: SalesInvoiceServiceImpl.finalise lines 231-234 vs GLPostingSafeInvoker.postSaleInNewTx lines 96-108
  - fix: Make the stamped base gross equal the posted base gross: either stamp baseGross = convert(net)+convert(vat) (matching the poster), or have the poster derive baseGross via fxConverter.balancingPlug from the stamped value, so the sub-ledger triple and the GL AR/cash leg are byte-identical.

- **[LOW] Revaluation amounts hardcode DEFAULT_BASE_MINOR_UNITS=2, ignoring the company base currency's actual minor units (TZS=0)**
  - file: FxRevaluationRunServiceImpl.computeRevalLines lines 89, 400-403, 441-448
  - fix: Resolve base minor units from the currencies master (or the shared baseMinorUnits helper) for the company's base currency and use that scale in computeRevalLines instead of the hardcoded 2.

- **[MEDIUM] Swallowed GL failure leaves run PREVIEWED with false-success DTO/outbox/audit**
  - file: FxRevaluationRunServiceImpl.post 234-280; postRevaluationJournal null at 524/539/548
  - fix: Distinguish no-exposure from post-failed: call GLPostingService.post directly (already MANDATORY tx) or throw when revalLines non-empty and netAdj non-zero but posting failed.

- **[MEDIUM] Base minor-units hardcoded to 2 instead of resolved from the currencies master**
  - file: FxRevaluationRunServiceImpl.DEFAULT_BASE_MINOR_UNITS=2 line 89; used 401/403/442/448
  - fix: Resolve base minor units from the currencies master and use that scale; keep the constant only as a fallback.

- **[LOW] Run-number generation can collide under the (company,run_number) unique constraint**
  - file: FxRevaluationRunServiceImpl.generateRunNumber 578-583
  - fix: Use the code_sequence allocator (the FXR-#### pattern) or include the fiscal period id / a per-company monotonic counter.

- **[MEDIUM] rate_at stamped as Instant.now() (wall-clock) instead of the rate's effective date â€” contradicts the entity contract and weakens the immutable audit triple**
  - file: CurrencyConversionServiceImpl.convert (line 73) and ConvertedAmount.identity (line 23); consumed by SalesInvoiceServiceImpl.finalise line 235, ArReceiptServiceImpl line 165, ApPayment lines 159/253, BillMatch line 428
  - fix: Either carry the CurrencyRate's effective_date (or its id/uid) through ConvertedAmount and stamp that, or rename/document rate_at honestly as 'stamping instant'. Best: add a rate_id/effective_date to the triple so the immutable stamp is provably tied to one currency_rates row.

- **[MEDIUM] Date skew between finalise stamping date (LocalDate.now(), system zone) and posting date (finalisedAt â†’ UTC LocalDate) can select different rate rows**
  - file: SalesInvoiceServiceImpl.finalise line 232 (LocalDate.now()) vs SalesPostingHandler.postSalesEntry lines 115-117 (finalisedAt.atZone(UTC).toLocalDate())
  - fix: Use a single, explicit posting/effective date everywhere (e.g. derive both finalise stamp and poster date from finalisedAt in the same zone, or store the effective LocalDate on the invoice and reuse it). Do not mix LocalDate.now() (default zone) with UTC-derived dates.

- **[MEDIUM] Minor-units source is inconsistent and, in AR reallocate, scales BASE amounts by the FOREIGN currency's minor units**
  - file: ArReceiptServiceImpl.reallocate lines 395 & 415 (baseMinorUnits(inv.getCurrency()) / baseMinorUnits(currency=receipt currency)); hardcoded baseMinorUnits in ArReceiptServiceImpl 557-564 & ApPaymentServiceImpl 477-484 vs CurrencyConversionServiceImpl.resolveMinorUnits 88-92 (currencies master)
  - fix: In reallocate, resolve baseScale from the COMPANY base currency (load company.baseCurrency), not the receipt/invoice currency. Replace the hardcoded baseMinorUnits switch with the shared currencies-master lookup (CurrencyConversionService / a single MinorUnits helper) so all paths use one source of truth.

- **[MEDIUM] GLPostingSafeInvoker.postSaleInNewTx swallows FxRateNotFoundException â€” a missing rate silently leaves the sale unposted to GL**
  - file: GLPostingSafeInvoker.postSaleInNewTx lines 96, 102, 133-138
  - fix: Catch FxRateNotFoundException separately from generic GL-config exceptions and either rethrow (so the dispatch records a hard FX anomaly / retries) or surface it via a distinct anomaly status, rather than collapsing it into the generic 'GL not configured' warn-and-null.

- **[MEDIUM] AR receipt day-1 GL legs are now summed from per-allocation slices each rounded to base minor units â€” latent byte-identity regression vs the pre-FX single-amount legs**
  - file: ArReceiptServiceImpl.recordAndAllocate lines 184, 199-203, 275-278, 295-296, 324-326
  - fix: On the identity path (settlementRate==1 and all invoiceRates==1) post Cash DR / AR CR from the receipt face amount directly (the old code path) instead of from re-summed, re-rounded slices; or compute the control legs as exact arithmetic complements (balancingPlug) so accumulated per-slice rounding cannot shift the totals.

- **[LOW] Document currency is not normalized/validated at creation, so a non-canonical base code misses the identity short-circuit and is forced into a (failing) rate lookup**
  - file: SalesInvoiceServiceImpl.create line 178 (req.currency() stored verbatim); CurrencyConversionServiceImpl.convert line 62 (case-sensitive fromCode.equals(toCode))
  - fix: Normalize currency codes to uppercase/trimmed and validate against the active currencies master at document-creation time, so the identity short-circuit reliably fires for base-currency documents and unknown codes are rejected up front.

