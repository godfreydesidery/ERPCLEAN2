package com.erp.platform.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Append-only record of every access-significant action in the system (DATA-MODEL §1.11,
 * ADR-0004). Rows are inserted and never updated or deleted — the application exposes no mutating
 * path (D-5). This entity is intentionally NOT a {@code UidEntity}: it is an internal table with no
 * external uid (ADR-0001 D-G).
 *
 * <p>{@code @Getter} generates read accessors; no setters are generated. All fields are set via the
 * public constructor and remain immutable for the lifetime of the instance.
 */
@Entity
@Table(name = "audit_logs")
@Getter
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK to {@code app_user.id}; NULL for system/bootstrap events or unknown-username login fails. */
    @Column(name = "actor_user_id")
    private Long actorUserId;

    /** Dot-separated action code from {@link AuditActions}, e.g. {@code USER.CREATE}. */
    @Column(name = "action", nullable = false, length = 80)
    private String action;

    /** Entity name of the target row, e.g. {@code app_user}, {@code user_branch}. */
    @Column(name = "target_type", nullable = false, length = 60)
    private String targetType;

    /** Internal id of the target row; NULL when no single row is the target (e.g. LOGIN.FAIL). */
    @Column(name = "target_id")
    private Long targetId;

    /** External uid of the target row where applicable. */
    @Column(name = "target_uid", length = 26)
    private String targetUid;

    /** Company context in which the action occurred; NULL for unauthenticated events. */
    @Column(name = "company_id")
    private Long companyId;

    /** Branch context in which the action occurred; NULL for unauthenticated events. */
    @Column(name = "branch_id")
    private Long branchId;

    /**
     * JSON payload with additional context (before/after status, etc.). Stored as JSONB in
     * Postgres; mapped as a String column with the native JSONB type code so Hibernate validates
     * correctly against the {@code jsonb} column type.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail", columnDefinition = "jsonb")
    private String detail;

    /** Event timestamp; set to {@code Instant.now()} at record time by {@link AuditServiceImpl}. */
    @Column(name = "at", nullable = false)
    private Instant at;

    /** Client IP address; populated for login events; NULL for authenticated session events. */
    @Column(name = "ip", length = 45)
    private String ip;

    /**
     * Owning tenant (ADR-0062). V99 created this column and V101 backfilled the rows that existed
     * then — but nothing has WRITTEN it since, because the entity had no field for it. Every audit
     * row created after V99 is therefore unattributed, which is why
     * {@code AuditReadService}'s tenant predicate has to stay NULL-tolerant: a strict one would hide
     * the entire recent history from the very people who need to read it.
     *
     * <p>Nullable, and stays nullable: unauthenticated events (login, lockout) have no tenant to
     * record, and inventing one would be a guess written into an append-only table.
     */
    @Column(name = "organisation_id")
    private Long organisationId;

    /** For JPA only — no direct use in application code. */
    protected AuditLog() {
    }

    /** Full constructor — the only public construction path; keeps every field final-in-effect. */
    public AuditLog(Long actorUserId,
                    String action,
                    String targetType,
                    Long targetId,
                    String targetUid,
                    Long companyId,
                    Long branchId,
                    String detail,
                    Instant at,
                    String ip,
                    Long organisationId) {
        this.actorUserId = actorUserId;
        this.action      = action;
        this.targetType  = targetType;
        this.targetId    = targetId;
        this.targetUid   = targetUid;
        this.companyId   = companyId;
        this.branchId    = branchId;
        this.organisationId = organisationId;
        this.detail      = detail;
        this.at          = at;
        this.ip          = ip;
    }
}
