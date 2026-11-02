export type UserType = 'S' | 'A' | 'O';

/**
 * Who is acting. Built from the verified JWT only (CLAUDE.md §7) and passed to every DML call,
 * which uses it for tenant scoping and audit columns.
 */
export interface RequestContext {
  /** md_user.id */
  userId: number;
  /** md_user.user_id (login id) — written to created_by / updated_by. */
  loginId: string;
  /** NULL only for Synergic super admins (S). */
  customerId: number | null;
  userType: UserType;
  /** td_user_session.session_id */
  sessionId?: string;
  /** Device holding the session (td_user_session.device_id). */
  deviceId?: string;
}

/** For CLI scripts and background jobs: platform-level, not tenant-scoped. */
export const SYSTEM_CONTEXT: RequestContext = {
  userId: 0,
  loginId: 'system',
  customerId: null,
  userType: 'S',
};
