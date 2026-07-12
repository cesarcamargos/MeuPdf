import {
  BlobServiceClient,
  ContainerSASPermissions,
  SASProtocol,
  StorageSharedKeyCredential,
  generateBlobSASQueryParameters,
} from '@azure/storage-blob';
import { requireEnv } from './auth';

/**
 * Issues a short-lived, HTTPS-only container SAS with read/write/list/create/delete.
 * The app talks to Blob storage directly with this SAS; the account key never leaves the Function.
 * (Single-user: the container holds only this user's data. Multi-user would need per-user
 * containers or prefix-scoped SAS via stored access policies.)
 */
export function makeContainerSas(): { sasUrl: string; expiresAt: string } {
  const conn = requireEnv('STORAGE_CONNECTION_STRING');
  const container = requireEnv('STORAGE_CONTAINER');

  const accountName = /AccountName=([^;]+)/.exec(conn)?.[1];
  const accountKey = /AccountKey=([^;]+)/.exec(conn)?.[1];
  if (!accountName || !accountKey) {
    throw new Error('STORAGE_CONNECTION_STRING is missing AccountName/AccountKey');
  }

  const credential = new StorageSharedKeyCredential(accountName, accountKey);
  const now = new Date();
  const startsOn = new Date(now.getTime() - 5 * 60 * 1000); // 5 min clock skew
  const expiresOn = new Date(now.getTime() + 60 * 60 * 1000); // 1 hour

  const sas = generateBlobSASQueryParameters(
    {
      containerName: container,
      permissions: ContainerSASPermissions.parse('racwdl'),
      startsOn,
      expiresOn,
      protocol: SASProtocol.Https,
    },
    credential,
  ).toString();

  const containerUrl = BlobServiceClient.fromConnectionString(conn)
    .getContainerClient(container).url;

  return { sasUrl: `${containerUrl}?${sas}`, expiresAt: expiresOn.toISOString() };
}
