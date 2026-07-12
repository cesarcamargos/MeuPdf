import { app, HttpRequest, HttpResponseInit } from '@azure/functions';
import { verifyToken } from '../auth';
import { makeContainerSas } from '../blob';

app.http('sas', {
  methods: ['POST'],
  authLevel: 'anonymous',
  route: 'sas',
  handler: async (req: HttpRequest): Promise<HttpResponseInit> => {
    const auth = verifyToken(req.headers.get('authorization'));
    if (!auth) {
      return { status: 401, jsonBody: { error: 'unauthorized' } };
    }
    const { sasUrl, expiresAt } = makeContainerSas();
    return { jsonBody: { sasUrl, expiresAt, userId: auth.userId } };
  },
});
