import { app, HttpRequest, HttpResponseInit } from '@azure/functions';
import * as bcrypt from 'bcryptjs';
import { requireEnv, signToken, userIdFromEmail } from '../auth';

app.http('login', {
  methods: ['POST'],
  authLevel: 'anonymous',
  route: 'login',
  handler: async (req: HttpRequest): Promise<HttpResponseInit> => {
    const body = (await req.json().catch(() => null)) as { email?: string; password?: string } | null;
    const email = (body?.email ?? '').trim().toLowerCase();
    const password = body?.password ?? '';

    const expectedEmail = requireEnv('AUTH_EMAIL').trim().toLowerCase();
    const passwordHash = requireEnv('AUTH_PASSWORD_HASH');

    const ok = !!email && !!password && email === expectedEmail && bcrypt.compareSync(password, passwordHash);
    if (!ok) {
      return { status: 401, jsonBody: { error: 'invalid_credentials' } };
    }

    const userId = userIdFromEmail(email);
    return { jsonBody: { token: signToken(userId), userId } };
  },
});
