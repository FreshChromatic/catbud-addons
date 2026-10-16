import { createHash, createPublicKey } from 'node:crypto';
import { writeFile } from 'node:fs/promises';
import { boundedBytes } from '../src/protocol.mjs';

const source = 'https://api.minecraftservices.com/publickeys';
const response = await fetch(source, { redirect: 'error', signal: AbortSignal.timeout(10000) });
if (!response.ok) throw new Error('Official public key retrieval failed: ' + response.status);
const data = JSON.parse(new TextDecoder().decode(await boundedBytes(response.body, 65536)));
if (
  !Array.isArray(data.playerCertificateKeys) ||
  !data.playerCertificateKeys.length ||
  data.playerCertificateKeys.length > 8
)
  throw new Error('Invalid official certificate key set');
const keys = data.playerCertificateKeys.map((value) => {
  const bytes = Buffer.from(value.publicKey, 'base64');
  const key = createPublicKey({ key: bytes, type: 'spki', format: 'der' });
  if (key.asymmetricKeyType !== 'rsa' || key.asymmetricKeyDetails.modulusLength !== 4096)
    throw new Error('Unsupported official certificate authority');
  console.log('Official certificate key SHA256:', createHash('sha256').update(bytes).digest('hex'));
  return bytes.toString('base64');
});
await writeFile(
  new URL('../src/official-certificate-keys.json', import.meta.url),
  JSON.stringify({ source, retrievedAt: new Date().toISOString(), keys }, null, 2) + '\n',
);
console.log('Updated public key snapshot. Review the change before deployment.');
