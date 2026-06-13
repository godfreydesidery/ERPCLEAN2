/**
 * Notifications module models — mirrors backend DTOs verbatim.
 * All Long/BigDecimal fields are typed string (wire serialisation).
 * Enums are typed as their literal string values.
 */

// ── Notification (inbox) ──────────────────────────────────────────────────────

export interface NotificationDto {
  id: string;
  uid: string;
  companyId: string;
  typeKey: string;
  channel: string;
  severity: string;
  title: string;
  body: string;
  sourceAggregateType: string;
  sourceUid: string;
  linkRoute: string;
  read: boolean;
  readAt: string | null;
  createdAt: string;
}

export interface UnreadCountDto {
  /** Long on the wire — serialised as string. */
  count: string;
}

// ── Notification Preference ────────────────────────────────────────────────────

export interface NotificationPreferenceDto {
  id: string;
  uid: string;
  companyId: string;
  userId: string;
  typeKey: string;
  muted: boolean;
  channelsEnabled: string | null;
}

export interface SetPreferenceRequest {
  muted: boolean;
  channelsEnabled: string;
}

// ── Admin: Notification Type ───────────────────────────────────────────────────

export type NotificationTypeStatus = 'ACTIVE' | 'INACTIVE' | 'ARCHIVED';

export interface NotificationTypeDto {
  id: string;
  uid: string;
  companyId: string;
  typeKey: string;
  displayName: string;
  description: string;
  audiencePermission: string;
  branchScoped: boolean;
  defaultChannels: string;
  severity: string;
  titleTemplate: string;
  bodyTemplate: string;
  linkRouteTemplate: string;
  companyEnabled: boolean;
  status: NotificationTypeStatus;
}

export interface SetCompanyTypeStateRequest {
  enabled: boolean;
}

// ── Admin: Notification Delivery ──────────────────────────────────────────────

export interface NotificationDeliveryDto {
  id: string;
  uid: string;
  /** Nullable — null for company-level NO_AUDIENCE diagnostics. */
  notificationId: string | null;
  companyId: string;
  typeKey: string;
  /** Nullable. */
  recipientUserId: string | null;
  channel: string;
  outcome: string;
  suppressionReason: string | null;
  /** Java short serialised as number. */
  attemptNo: number;
  error: string | null;
  triggerKey: string;
  attemptedAt: string;
  completedAt: string | null;
}
