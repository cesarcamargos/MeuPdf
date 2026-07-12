import { app, HttpResponseInit } from '@azure/functions';

app.http('health', {
  methods: ['GET'],
  authLevel: 'anonymous',
  route: 'health',
  handler: async (): Promise<HttpResponseInit> => ({ jsonBody: { ok: true } }),
});
