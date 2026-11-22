import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';

const origin = process.env.SDDP_TEST_ORIGIN;

async function post(path, body) {
  const response = await fetch(origin + '/api/v1' + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(15_000),
  });
  if (!response.ok) throw new Error(path + ' returned HTTP ' + response.status + ': ' + await response.text());
  return response;
}

for (let i = 0; i < 90; i++) {
  const health = await fetch(origin + '/actuator/health', { signal: AbortSignal.timeout(2_000) }).catch(() => null);
  if (health?.ok) break;
  if (i === 89) throw new Error('SDDP did not become ready');
  await new Promise(resolve => setTimeout(resolve, 1000));
}

async function exportOrders() {
  const project = await (await post('/projects', { name: 'offline-orders' })).json();
  const path = '/projects/' + project.id;
  const store = await (await post(path + '/services', { name: 'store', serviceType: 'CORE' })).json();
  const orders = await (await post(path + '/services', { name: 'orders', serviceType: 'EXTERNAL' })).json();
  const document = JSON.parse(await readFile('/qa/orders.json', 'utf8'));
  const selection = ['GET /orders/{id}', 'POST /orders'];
  const imported = await (await post(path + '/services/' + orders.id + '/apis/openapi/import',
    { document, operations: selection })).json();
  assert.equal(imported.created.length, 2);
  for (const [index, targetApiId] of imported.created.entries()) {
    await post(path + '/connections', {
      environment: 'DEV',
      sourceServiceId: store.id,
      targetServiceId: orders.id,
      targetApiId,
      transportType: 'REST',
      connectionSettings: { host: 'orders', port: 8080, target_endpoint: '/' },
      mappingRules: index === 0 ? null : {
        target_api: 'createOrder v1.0.0',
        transformations: [{ type: 'DIRECT_COPY', from: '$.sku', to: '$.productCode' }],
      },
    });
  }
  await writeFile('/qa/snapshot.json', await (await post(path + '/snapshot', {})).text());
}

async function importOrders() {
  const snapshot = JSON.parse(await readFile('/qa/snapshot.json', 'utf8'));
  const restored = await (await post('/projects/import', snapshot)).json();
  await writeFile('/qa/project-id', restored.id);
  await writeFile('/qa/ir.json', await (await post('/projects/' + restored.id + '/ir?environment=DEV', {})).text());
  const artifact = await post('/codegen/artifact', {
    projectId: restored.id, environment: 'DEV', packageName: 'pilot', clientName: 'Orders', version: '1.0.0',
  });
  await writeFile('/qa/orders.zip', new Uint8Array(await artifact.arrayBuffer()));
}

if (process.argv[2] === 'export') await exportOrders();
else if (process.argv[2] === 'import') await importOrders();
else throw new Error('Choose export or import');
