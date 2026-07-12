import * as jwt from 'jsonwebtoken';
import { createHash } from 'crypto';

/** Stable, opaque user id derived from the email (used to prefix blobs: users/<userId>/...). */
export function userIdFromEmail(email: string): string {
  return createHash('sha256').update(email.trim().toLowerCase()).digest('hex').slice(0, 32);
}

export function signToken(userId: string): string {
  const secret = requireEnv('JWT_SECRET');
  return jwt.sign({ userId }, secret, { expiresIn: '30d' });
}

export function verifyToken(authHeader?: string | null): { userId: string } | null {
  if (!authHeader || !authHeader.startsWith('Bearer ')) return null;
  try {
    const decoded = jwt.verify(authHeader.slice(7), requireEnv('JWT_SECRET')) as { userId?: string };
    return decoded.userId ? { userId: decoded.userId } : null;
  } catch {
    return null;
  }
}

export function requireEnv(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`Missing required app setting: ${name}`);
  return value;
}
