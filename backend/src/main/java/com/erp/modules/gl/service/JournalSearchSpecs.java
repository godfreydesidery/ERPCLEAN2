package com.erp.modules.gl.service;

import com.erp.modules.gl.domain.dto.JournalSearchCriteria;
import com.erp.modules.gl.domain.entity.JournalBatch;
import com.erp.modules.gl.domain.entity.JournalEntry;
import com.erp.modules.gl.domain.entity.JournalLine;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/**
 * Journal list filters (ACC-19), always inside one company. Only the clauses whose filter is set
 * are added — no {@code (:p IS NULL OR …)} binds, which Postgres cannot type.
 */
final class JournalSearchSpecs {

    private JournalSearchSpecs() {
    }

    static Specification<JournalEntry> matching(Long companyId, JournalSearchCriteria c,
                                                Long accountId) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("companyId"), companyId));
            if (c.from() != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("postingDate"), c.from()));
            }
            if (c.to() != null) {
                where.add(cb.lessThanOrEqualTo(root.get("postingDate"), c.to()));
            }
            if (c.sourceType() != null) {
                where.add(cb.equal(root.get("sourceType"), c.sourceType()));
            }
            if (accountId != null) {
                Subquery<Long> onAccount = query.subquery(Long.class);
                Root<JournalLine> line = onAccount.from(JournalLine.class);
                onAccount.select(line.get("entryId")).where(
                        cb.equal(line.get("companyId"), companyId),
                        cb.equal(line.get("accountId"), accountId));
                where.add(root.get("id").in(onAccount));
            }
            if (c.q() != null && !c.q().isBlank()) {
                String text = c.q().trim();
                String like = "%" + text.toLowerCase(Locale.ROOT)
                        .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";

                Subquery<Long> byBatch = query.subquery(Long.class);
                Root<JournalBatch> batch = byBatch.from(JournalBatch.class);
                byBatch.select(batch.get("id")).where(
                        cb.equal(batch.get("companyId"), companyId),
                        cb.like(cb.lower(batch.get("batchNumber")), like, '\\'));

                Subquery<Long> byMemo = query.subquery(Long.class);
                Root<JournalLine> memoLine = byMemo.from(JournalLine.class);
                byMemo.select(memoLine.get("entryId")).where(
                        cb.equal(memoLine.get("companyId"), companyId),
                        cb.like(cb.lower(memoLine.get("lineMemo")), like, '\\'));

                // A document number in one journal's description finds every journal of that
                // document: the SALES journal carries only the invoice uid, its COGS sibling the
                // invoice number.
                Subquery<String> sameDocument = query.subquery(String.class);
                Root<JournalEntry> described = sameDocument.from(JournalEntry.class);
                sameDocument.select(described.get("sourceRef")).where(
                        cb.equal(described.get("companyId"), companyId),
                        cb.isNotNull(described.get("sourceRef")),
                        cb.like(cb.lower(described.get("description")), like, '\\'));

                where.add(cb.or(
                        cb.like(cb.lower(root.get("description")), like, '\\'),
                        cb.equal(root.get("sourceRef"), text),
                        cb.like(cb.lower(root.get("externalRef")), like, '\\'),
                        root.get("batchId").in(byBatch),
                        root.get("id").in(byMemo),
                        root.get("sourceRef").in(sameDocument)));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }
}
