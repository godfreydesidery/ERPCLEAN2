/** Mirrors the backend UserDto. Every numeric id typed `string` (wire contract). */

export interface User {
  id: string;
  uid: string;
  username: string;
  displayName: string;
  email: string | null;
  phone: string | null;
  isRoot: boolean;
  status: string;
  locked: boolean;
  lastLoginAt: string | null;
}

export interface CreateUserRequest {
  username: string;
  displayName: string;
  password: string;
  email?: string;
  phone?: string;
}

export interface UpdateUserRequest {
  displayName: string;
  email?: string;
  phone?: string;
}

export interface SetPasswordRequest {
  password: string;
}
