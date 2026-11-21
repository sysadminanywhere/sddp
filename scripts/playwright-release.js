async page => {
  const origin = new URL(page.url()).origin;
  const url = path => new URL(path, origin).href;
  const receiveJson = async response => {
    if (!response.ok()) {
      throw new Error(response.status() + ' ' + response.url() + ': ' + await response.text());
    }
    return response.json();
  };
  const post = async (path, data) => receiveJson(
    await page.request.post(url(path), { data, headers: { Origin: origin } })
  );
  const expect = (condition, message) => {
    if (!condition) throw new Error(message);
  };

  const projectName = 'browser-project-' + Date.now();
  await page.getByRole('textbox', { name: 'System name' }).fill(projectName);
  const created = page.waitForResponse(response =>
    new URL(response.url()).pathname === '/api/v1/projects' && response.request().method() === 'POST'
  );
  await page.getByRole('button', { name: 'Create project' }).click();
  const project = await receiveJson(await created);
  await page.getByRole('heading', { name: projectName }).waitFor();

  const base = '/api/v1/projects/' + project.id;
  const source = await post(base + '/services', { name: 'orders', serviceType: 'CORE' });
  const target = await post(base + '/services', { name: 'billing', serviceType: 'EXTERNAL' });
  const addApi = (service, name, field) => post(base + '/services/' + service.id + '/apis', {
    name, version: '1', pattern: 'REQUEST_RESPONSE',
    requestSchema: { type: 'object', properties: { [field]: { type: 'string' } }, required: [field] }
  });
  const sourceApi = await addApi(source, 'sendOrder', 'orderId');
  await page.goto(url('/service.html?projectId=' + project.id + '&serviceId=' + target.id));
  await page.getByText('Import OpenAPI', { exact: true }).click();
  await page.getByRole('button', { name: 'OpenAPI JSON file' }).setInputFiles('docs/examples/orders.openapi.json');
  await page.getByRole('button', { name: 'Preview operations' }).click();
  await page.getByRole('checkbox', { name: 'GET /orders/{id} · getOrder' }).waitFor();
  await page.getByRole('checkbox', { name: 'POST /orders · createOrder' }).waitFor();
  await page.getByRole('button', { name: 'Import selected operations' }).click();
  await page.getByRole('link', { name: 'createOrder', exact: true }).waitFor();
  const targetApis = await receiveJson(await page.request.get(url(base + '/services/' + target.id + '/apis')));
  const targetApi = targetApis.find(operation => operation.name === 'createOrder');
  expect(targetApi?.httpOperation?.method === 'POST' && targetApis.length === 2, 'OpenAPI import did not preserve both operations');

  const graphUrl = url('/graph.html?projectId=' + project.id + '&environment=DEV');
  await page.goto(graphUrl);
  const node = id => page.locator('.react-flow__node[data-id="' + id + '"]');
  const sourceNode = node(source.id);
  const targetNode = node(target.id);
  await sourceNode.waitFor();
  await targetNode.waitFor();
  await page.locator('#fitView').click();
  await sourceNode.locator('.react-flow__handle.source')
    .dragTo(targetNode.locator('.react-flow__handle.target'));

  const dialog = page.getByRole('dialog', { name: 'New connection' });
  await dialog.locator('#connectSourceApi').selectOption(sourceApi.id);
  await dialog.locator('#connectTargetApi').selectOption(targetApi.id);
  expect(await dialog.locator('#connectEndpoint').inputValue() === '/orders', 'Imported path was not suggested');
  const connectionCreated = page.waitForResponse(response =>
    new URL(response.url()).pathname === base + '/connections' && response.request().method() === 'POST'
  );
  await dialog.getByRole('button', { name: 'Create connection' }).click();
  await receiveJson(await connectionCreated);
  await dialog.waitFor({ state: 'hidden' });

  const positionSaves = [source, target].map(service => page.waitForResponse(response =>
    new URL(response.url()).pathname === base + '/services/' + service.id && response.request().method() === 'PUT'
  ).then(receiveJson));
  await page.getByRole('button', { name: 'Auto-layout' }).click();
  const savedServices = await Promise.all(positionSaves);
  const reloadPosition = page.waitForResponse(response =>
    new URL(response.url()).pathname === base + '/services' && response.request().method() === 'GET'
  );
  await page.reload();
  const services = await receiveJson(await reloadPosition);
  for (const saved of savedServices) {
    expect(Number.isFinite(saved.positionX) && Number.isFinite(saved.positionY), 'Graph position was not saved');
    const reloaded = services.find(service => service.id === saved.id);
    expect(reloaded?.positionX === saved.positionX && reloaded?.positionY === saved.positionY,
      'Graph position did not survive reload');
  }
  const edge = page.locator('.react-flow__edge-interaction');
  const center = await edge.evaluate(path => {
    const point = path.getPointAtLength(path.getTotalLength() / 2);
    const { x, y } = point.matrixTransform(path.getScreenCTM());
    return { x, y };
  });
  await page.mouse.click(center.x, center.y);
  await page.locator('#selectedConnection').getByRole('link', { name: 'Edit' }).click();

  await page.locator('#endpoint').waitFor();
  await page.locator('#addMappingRule').click();
  await page.locator('.mapping-source select').selectOption('$.orderId');
  await page.locator('.mapping-rule > select').last().selectOption('$.productCode');
  const edited = page.waitForResponse(response =>
    new URL(response.url()).pathname.startsWith(base + '/connections/') && response.request().method() === 'PUT'
  );
  await page.locator('#saveConnection').click();
  const update = await edited;
  expect(update.ok(), 'Editing the connection failed: HTTP ' + update.status());

  await page.goto(url('/project.html?projectId=' + project.id + '&environment=DEV'));
  await page.locator('[data-tab="environments"]').click();
  await page.locator('#copySource').selectOption('DEV');
  await page.locator('#copyTarget').selectOption('PROD');
  // Record both native prompts while allowing the CLI to complete without manual input.
  await page.evaluate(() => {
    window.releaseConfirmations = [];
    window.confirm = message => {
      window.releaseConfirmations.push(message);
      return true;
    };
  });
  const copied = page.waitForResponse(response =>
    new URL(response.url()).pathname === base + '/connections/copy' && response.request().method() === 'POST'
  );
  await page.locator('#copyConnections').click();
  const confirmations = await page.evaluate(() => window.releaseConfirmations);
  expect(confirmations.length === 2 && confirmations[1].includes('PROD'),
    'The protected-environment confirmation is missing');
  const promotion = await copied;
  expect(promotion.request().headers()['x-sddp-confirm-protected'] === 'true',
    'The copy request did not confirm the protected write');
  expect((await receiveJson(promotion)).copied === 1, 'Expected one promoted connection');

  await page.goto(url('/project.html?projectId=' + project.id + '&environment=PROD'));
  await page.locator('[data-tab="build"]').click();
  const generated = page.waitForResponse(response =>
    new URL(response.url()).pathname === '/api/v1/codegen/generate'
  );
  await page.getByRole('button', { name: 'Generate', exact: true }).click();
  const result = await receiveJson(await generated);
  const java = result.files['GeneratedClient.java'];
  expect(java?.includes('/orders') && java.includes('$.productCode') && java.includes('$.orderId'),
    'The generated client did not use the promoted endpoint and mapping');
  await page.locator('#codegenResult').getByText('GeneratedClient.java', { exact: true }).waitFor();
  return 'SDDP browser release check passed';
}
