# API Contract — notifications module (Phase-B frontend)

**Nav group:** Notifications  
**Permission codes (use VERBATIM — do not invent):** NOTIFICATION.VIEW, NOTIFICATION.PREFERENCE.MANAGE, NOTIFICATION.ADMIN

## Module notes
PAGINATION: Two list endpoints are paginated (Spring Pageable + PageMeta in the ApiResponse meta): GET /api/v1/notifications (inbox) and GET /api/v1/admin/notifications/deliveries (delivery log). Send Spring page params ?page=0&size=20&sort=... ; read total/page from response meta (PageMeta). The other two "list" endpoints are NOT paginated and return a plain array: GET /api/v1/notification-preferences and GET /api/v1/admin/notifications/types.

ENVELOPE: Every endpoint that returns a body wraps it in ApiResponse<T> = { data: T, meta: PageMeta|null }. Paginated list endpoints put the array in data and pagination in meta (via ApiResponse.ok(page.getContent(), PageMeta.from(page))). Non-paginated returns put the object/array directly in data with null meta. The two mark endpoints (POST .../read and POST .../read-all) return HTTP 204 No Content with NO body.

ACTIONS / "LIFECYCLE": Notifications has NO release/complete/cancel/approve/post-style workflow â€” it is a cross-cutting alerting module (ADR-0024). Notifications are created entirely server-side by the SYSTEM (the NotificationDispatcher outbox consumer + the @Scheduled NotificationScanner); there is NO create/update/delete endpoint exposed to the UI for notifications, types, or deliveries. The only user actions on a notification are READ-state transitions:
  - POST /api/v1/notifications/uid/{uid}/read  -> mark ONE notification read. Precondition: the notification's recipient_user_id must equal the calling user (service enforces recipient-is-me, BR-NOTIF-04) AND it must be in the user's active company. is_read is the only mutable field. Idempotent (re-marking an already-read row is harmless). Perm is @perm.scoped(#uid,'notification','NOTIFICATION.VIEW').
  - POST /api/v1/notifications/read-all -> mark ALL my unread read in the active company.
For NOTIFICATION TYPES the only mutation is the admin per-company on/off toggle: PUT /api/v1/admin/notifications/types/{typeKey}/state with body {enabled:boolean}. This sets company_enabled; when false the type never fires for that company (deliveries logged SUPPRESSED reason COMPANY_TYPE_OFF). This action is AUDITED. Path var is the typeKey string (e.g. LOW_STOCK), NOT a uid.
For PREFERENCES the only mutation is the per-user upsert: PUT /api/v1/notification-preferences/{typeKey} with body {muted:boolean, channelsEnabled:string}. Creates the preference row lazily on first set. channelsEnabled is a comma-joined channel set e.g. "IN_APP,EMAIL" (null = use type defaults). Path var is the typeKey string.

PATH-VAR NAMING GOTCHA: the mark-read endpoint nests uid under a literal /uid/ segment: POST /api/v1/notifications/uid/{uid}/read (the path is /uid/{uid}/read, not /{uid}/read). The ADR D-10 text shows /notifications/{uid}/read but the SHIPPED controller uses /uid/{uid}/read â€” trust the controller. The type-state and preference path vars are {typeKey} (a string key like LOW_STOCK / INVOICE_OVERDUE / PAYMENT_RECEIVED / APPROVAL_PENDING / GOODS_RECEIVED / DELIVERY_CONFIRMED), NOT a uid.

FILTERS (shipped, exact): inbox supports only ?unread=true|false (default false). The ADR mentions typeKey/severity filters on the inbox but they are NOT implemented in the shipped controller â€” do not build UI for them. Delivery log supports ?channel=EMAIL and ?outcome=FAILED (both optional, server upper-cases them); if both passed, channel takes precedence (else-if). ADR mentions typeKey/recipient/date delivery filters but they are NOT in the shipped controller.

MONEY/CURRENCY: NONE. Per BR-NOTIF-13 the module holds no Money type and posts no GL. Any monetary figure that appears in a notification title/body is already a pre-formatted display string baked into the title/body text fields at fire time (e.g. "TZS 1,250,000"); there are no numeric amount or currency-code fields on any DTO.

REPORT ENDPOINTS: none. The delivery log (GET /admin/notifications/deliveries) is the closest thing to a report â€” a paged audit trail of every dispatch attempt with outcome/suppressionReason/error/attemptNo/triggerKey. It is a normal paged list (array in data, PageMeta in meta), not a special report shape.

CHILD/NESTED RESOURCES: none in the strict REST sense. The delivery log rows reference a parent notification via notificationId (nullable â€” null rows are company-level NO_AUDIENCE diagnostics with null recipientUserId). Notification types and preferences are keyed by typeKey (shared logical key set). There is no /notifications/{uid}/deliveries sub-collection â€” deliveries are only listed company-wide via the admin endpoint.

UNREAD BADGE: GET /api/v1/notifications/unread-count returns UnreadCountDto {count:long} for the shell badge (NFR-NOTIF-08, backed by a partial index). Only IN_APP unread notifications count.

ENUM-AS-STRING ON THE WIRE: channel/severity/outcome/suppressionReason all serialize as their literal string values (no ordinals). NotificationDelivery.recipientUserId and notificationId are nullable. NotificationDelivery.attemptNo is a Java short -> serializes as a number.


## Resource: `notifications`  (base `/api/v1/notifications`)

**Primary DTO fields:** id:string, uid:string, companyId:string, typeKey:string, channel:string, severity:string, title:string, body:string, sourceAggregateType:string, sourceUid:string, linkRoute:string, read:boolean, readAt:Instant, createdAt:Instant

| Method | Path | Perm | Purpose | Request DTO | Response DTO |
|---|---|---|---|---|---|
| GET | `/api/v1/notifications` | `NOTIFICATION.VIEW` | list | query params: unread:boolean (default false), Spring Pageable (page,size,sort) | ApiResponse<List<NotificationDto>> (paginated; PageMeta in meta). NotificationDto fields: id:string, uid:string, companyId:string, typeKey:string, channel:string, severity:string, title:string, body:string, sourceAggregateType:string, sourceUid:string, linkRoute:string, read:boolean, readAt:Instant, createdAt:Instant |
| GET | `/api/v1/notifications/unread-count` | `NOTIFICATION.VIEW` | report |  | ApiResponse<UnreadCountDto> { count:string (long) } |
| POST | `/api/v1/notifications/uid/{uid}/read` | `NOTIFICATION.VIEW` | mark-read (single) |  | 204 No Content (void) |
| POST | `/api/v1/notifications/read-all` | `NOTIFICATION.VIEW` | mark-all-read |  | 204 No Content (void) |

## Resource: `notification-preferences`  (base `/api/v1/notification-preferences`)

**Primary DTO fields:** id:string, uid:string, companyId:string, userId:string, typeKey:string, muted:boolean, channelsEnabled:string

| Method | Path | Perm | Purpose | Request DTO | Response DTO |
|---|---|---|---|---|---|
| GET | `/api/v1/notification-preferences` | `NOTIFICATION.PREFERENCE.MANAGE` | list |  | ApiResponse<List<NotificationPreferenceDto>> (NOT paginated; meta null). NotificationPreferenceDto fields: id:string, uid:string, companyId:string, userId:string, typeKey:string, muted:boolean, channelsEnabled:string |
| PUT | `/api/v1/notification-preferences/{typeKey}` | `NOTIFICATION.PREFERENCE.MANAGE` | update (upsert preference) | SetPreferenceRequest { muted:boolean, channelsEnabled:string } | ApiResponse<NotificationPreferenceDto> { id:string, uid:string, companyId:string, userId:string, typeKey:string, muted:boolean, channelsEnabled:string } |

## Resource: `admin-notifications`  (base `/api/v1/admin/notifications`)

**Status enum:** ACTIVE, INACTIVE, ARCHIVED

**Primary DTO fields:** NotificationTypeDto: id:string, uid:string, companyId:string, typeKey:string, displayName:string, description:string, audiencePermission:string, branchScoped:boolean, defaultChannels:string, severity:string, titleTemplate:string, bodyTemplate:string, linkRouteTemplate:string, companyEnabled:boolean, status:string

| Method | Path | Perm | Purpose | Request DTO | Response DTO |
|---|---|---|---|---|---|
| GET | `/api/v1/admin/notifications/types` | `NOTIFICATION.ADMIN` | list |  | ApiResponse<List<NotificationTypeDto>> (NOT paginated; meta null). NotificationTypeDto fields: id:string, uid:string, companyId:string, typeKey:string, displayName:string, description:string, audiencePermission:string, branchScoped:boolean, defaultChannels:string, severity:string, titleTemplate:string, bodyTemplate:string, linkRouteTemplate:string, companyEnabled:boolean, status:string |
| PUT | `/api/v1/admin/notifications/types/{typeKey}/state` | `NOTIFICATION.ADMIN` | action: enable/disable type for company (toggle company_enabled; audited) | SetCompanyTypeStateRequest { enabled:boolean } | ApiResponse<NotificationTypeDto> (the updated type catalogue row, same fields as list) |
| GET | `/api/v1/admin/notifications/deliveries` | `NOTIFICATION.ADMIN` | list (delivery log) | query params: channel:string (optional, e.g. EMAIL), outcome:string (optional, e.g. FAILED), Spring Pageable (page,size,sort) | ApiResponse<List<NotificationDeliveryDto>> (paginated; PageMeta in meta). NotificationDeliveryDto fields: id:string, uid:string, notificationId:string (nullable), companyId:string, typeKey:string, recipientUserId:string (nullable), channel:string, outcome:string, suppressionReason:string, attemptNo:string (short), error:string, triggerKey:string, attemptedAt:Instant, completedAt:Instant |