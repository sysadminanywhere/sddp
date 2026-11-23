(() => {
  'use strict';
  const { state, esc, api, projectBase, projectUrl, serviceUrl, apiUrl, status, requireProject, fmtDate, latestApis, showFlash } = window.SDDP;
  const { t } = window.I18N;

  const byId = id => document.getElementById(id);
  const serviceId = new URLSearchParams(location.search).get('serviceId') || '';
  const initialProjectId = state.projectId;

  let project = null;
  let service = null;
  let apis = [];
  let topology = { nodes: [], edges: [] };
  let openApiSource = null;
  let openApiCatalog = null;
  let openApiSelection = new Set();
  let openApiVersion = '';

  function nodeName(id) {
    return topology.nodes.find(node => node.id === id)?.name || id;
  }

  function typeBadge(type) {
    const kind = String(type || 'CORE').toLowerCase();
    return `<span class="badge ${kind}">${esc(type || 'CORE')}</span>`;
  }

  function schemaFieldList(schema, depth = 0, prefix = '') {
    if (!schema || typeof schema !== 'object' || !schema.properties || typeof schema.properties !== 'object') return [];
    const required = new Set(Array.isArray(schema.required) ? schema.required : []);
    const items = [];
    for (const [name, property] of Object.entries(schema.properties)) {
      const value = property && typeof property === 'object' ? property : {};
      const type = value.type || (value.properties ? 'object' : value.items ? 'array' : 'any');
      items.push({ label: `${prefix}${name}`, type, required: required.has(name), depth });
      if (type === 'object' || value.properties) {
        items.push(...schemaFieldList(value, depth + 1, `${prefix}${name}.`));
      } else if (type === 'array' && value.items && typeof value.items === 'object') {
        items.push(...schemaFieldList(value.items, depth + 1, `${prefix}${name}[].`));
      }
    }
    return items;
  }

  function fieldCount(schema) {
    return schemaFieldList(schema).length;
  }

  function renderService() {
    byId('serviceTitle').textContent = service.name;
    byId('serviceSubtitle').textContent = t('service.subtitle', { type: service.serviceType, date: fmtDate(service.createdAt), count: latestApis(apis).length });
    byId('serviceTypeBadge').outerHTML = typeBadge(service.serviceType).replace('<span ', '<span id="serviceTypeBadge" ');
    byId('serviceName').value = service.name;
    byId('serviceType').value = service.serviceType;
    byId('serviceDescription').value = service.description || '';
    byId('breadcrumbProject').textContent = project?.name || t('service.breadcrumbProject');
    byId('breadcrumbProject').href = projectUrl();
    byId('breadcrumbService').textContent = service.name;
    byId('newApi').href = apiUrl(serviceId, null, 'create');
    const openApiUrl = projectBase() + '/services/' + encodeURIComponent(serviceId) + '/apis/openapi';
    byId('openApiPreviewForm').action = openApiUrl + '/preview';
    byId('openApiImportForm').action = openApiUrl + '/import';
    byId('openApiPanel').hidden = false;
    byId('asyncApiPanel').hidden = false;
    document.title = `${service.name} · ${project?.name || t('service.breadcrumbProject')} · SDDP`;
  }

  function renderApis() {
    const latest = latestApis(apis);
    byId('apiCount').textContent = String(latest.length);
    if (!latest.length) {
      byId('apiList').innerHTML = `<div class="empty-state">${t('service.emptyApis')}</div>`;
      return;
    }
    byId('apiList').innerHTML = latest.map(item => {
      const viewUrl = apiUrl(serviceId, item.id, 'view');
      const operation = item.eventOperation
        ? `${t('asyncapi.' + item.eventOperation.direction.toLowerCase())} · ${item.eventOperation.topic}`
        : item.httpOperation ? `${item.httpOperation.method} ${item.httpOperation.path}` : item.pattern;
      return `
      <article class="api-card">
        <header>
          <div>
            <h3><a class="api-title-link" href="${viewUrl}">${esc(item.name)}</a> <span class="badge accent">v${esc(item.version)}</span></h3>
            <small>${esc(operation)} · request: ${fieldCount(item.requestSchema)} · response: ${fieldCount(item.responseSchema)}</small>
          </div>
          <div class="button-row">
            <a class="button-link secondary" href="${viewUrl}">${t('service.open')}</a>
            <a class="button-link secondary" href="${apiUrl(serviceId, item.id, 'edit')}">${t('service.edit')}</a>
            <a class="button-link secondary" href="${apiUrl(serviceId, item.id, 'version')}">${t('service.newVersion')}</a>
            <a class="button-link danger" href="${apiUrl(serviceId, item.id, 'delete')}">${t('service.delete')}</a>
          </div>
        </header>
        <p class="hint">${esc(item.description || t('common.noDescription'))}</p>
      </article>`;
    }).join('');
  }

  function renderConnectionList(element, edges) {
    if (!edges.length) {
      element.innerHTML = `<div class="empty-state">${t('service.noConnections')}</div>`;
      return;
    }
    element.innerHTML = edges.map(edge => `
      <div class="list-row">
        <div class="list-main">
          <strong>${esc(nodeName(edge.source))} <span class="arrow">→</span> ${esc(nodeName(edge.target))}</strong>
          <small>${esc(edge.sourceApi || t('service.noSourceApi'))} → ${esc(edge.targetApi || t('service.noTargetApi'))}</small>
          <small>${esc(edge.transportType)} · ${edge.mappingRules ? t('service.mappingConfigured') : t('service.noMapping')}</small>
        </div>
      </div>`).join('');
  }

  function renderConnections() {
    renderConnectionList(byId('outboundList'), topology.edges.filter(edge => edge.source === serviceId));
    renderConnectionList(byId('inboundList'), topology.edges.filter(edge => edge.target === serviceId));
  }

  async function load() {
    if (!requireProject() || !serviceId) {
      byId('serviceTitle').textContent = t('service.notSelected');
      byId('serviceSubtitle').textContent = t('service.notSelectedHint');
      byId('serviceForm').hidden = true;
      return;
    }
    try {
      status(t('service.loadingService'));
      [project, service, apis, topology] = await Promise.all([
        api(projectBase()),
        api(`${projectBase()}/services/${encodeURIComponent(serviceId)}`),
        api(`${projectBase()}/services/${encodeURIComponent(serviceId)}/apis`),
        api(`${projectBase()}/topology?environment=${encodeURIComponent(state.environment)}`),
      ]);
      renderService();
      renderApis();
      renderConnections();
      if (!showFlash()) status(t('service.loaded', { name: service.name, count: latestApis(apis).length }));
    } catch (error) {
      status(error.message, 'error');
    }
  }

  function resetOpenApiPreview() {
    openApiSource = null;
    openApiCatalog = null;
    openApiSelection.clear();
    openApiVersion = '';
    byId('openApiVersion').value = '';
    byId('openApiVersion').removeAttribute('aria-invalid');
    byId('openApiVersionField').hidden = true;
    byId('openApiImportForm').hidden = true;
    byId('openApiWarnings').hidden = true;
    byId('openApiSearch').hidden = true;
    byId('openApiBack').hidden = true;
    byId('openApiFilter').value = '';
    byId('openApiStatus').textContent = '';
  }

  function filterOpenApiOperations() {
    const query = byId('openApiFilter').value.trim().toLowerCase();
    for (const row of byId('openApiOperations').children) {
      row.hidden = !row.textContent.toLowerCase().includes(query);
    }
  }

  function renderOpenApiPreview(preview) {
    const catalog = preview.selectionRequired;
    openApiVersion = preview.version;
    byId('openApiVersionField').hidden = !preview.operations.some(operation => operation.exists && operation.comparison);
    const warnings = byId('openApiWarnings');
    warnings.replaceChildren(...preview.warnings.map(warning => {
      const item = document.createElement('li');
      item.textContent = warning;
      return item;
    }));
    warnings.hidden = preview.warnings.length === 0;
    byId('openApiStatus').textContent = t(catalog ? 'openapi.catalogResult' : 'openapi.previewResult', {
      version: preview.version, count: preview.operations.length,
    });
    byId('openApiOperations').replaceChildren(...preview.operations.map((operation, index) => {
      const row = document.createElement('div');
      const checkbox = document.createElement('input');
      const label = document.createElement('label');
      checkbox.type = 'checkbox';
      checkbox.name = 'operations';
      checkbox.id = 'openapi-operation-' + index;
      checkbox.value = operation.key;
      if (operation.exists && operation.comparison) checkbox.dataset.versionConflict = 'true';
      checkbox.checked = catalog ? openApiSelection.has(operation.key) : !operation.exists || openApiSelection.has(operation.key);
      label.htmlFor = checkbox.id;
      label.textContent = operation.method + ' ' + operation.path + ' · ' + operation.name
        + (operation.exists ? ' · ' + t('openapi.existing') : '');
      const option = document.createElement('div');
      option.className = 'button-row';
      option.append(checkbox, label);
      row.append(option);
      if (operation.comparison) row.append(openApiComparison(operation.comparison));
      return row;
    }));
    byId('openApiImportForm').hidden = preview.operations.length === 0;
    byId('openApiSearch').hidden = !catalog;
    byId('openApiCheck').hidden = !catalog;
    byId('openApiImport').hidden = catalog;
    byId('openApiBack').hidden = catalog || !openApiCatalog;
    if (catalog) {
      filterOpenApiOperations();
      byId('openApiFilter').focus();
    } else if (preview.operations.length > 0) {
      byId('openApiOperationsLabel').focus();
    } else if (openApiCatalog) {
      byId('openApiBack').focus();
    }
  }

  function openApiComparison(comparison) {
    const details = document.createElement('details');
    const summary = document.createElement('summary');
    summary.textContent = t('openapi.comparison', { version: comparison.baselineVersion });
    const list = document.createElement('ul');
    for (const change of comparison.changes) {
      const item = document.createElement('li');
      item.textContent = t('openapi.change.' + change.kind, { path: change.path });
      list.append(item);
    }
    details.append(summary, list);
    if (comparison.truncated) {
      const note = document.createElement('p');
      note.textContent = t('openapi.changeMore');
      details.append(note);
    }
    return details;
  }

  async function readOpenApiFile(file) {
    const text = await file.text();
    if (/\.ya?ml$/i.test(file.name)) return { yaml: text };
    try {
      return { document: JSON.parse(text) };
    } catch {
      throw new Error(t('openapi.invalidFile'));
    }
  }

  function bindOpenApi() {
    const fileInput = byId('openApiFile');
    const previewForm = byId('openApiPreviewForm');
    const previewUrl = () => new URL(previewForm.action).pathname;
    const previewButton = byId('openApiPreview');
    const versionInput = byId('openApiVersion');
    versionInput.addEventListener('input', () => versionInput.removeAttribute('aria-invalid'));
    fileInput.addEventListener('change', resetOpenApiPreview);
    byId('openApiFilter').addEventListener('input', filterOpenApiOperations);
    byId('openApiBack').addEventListener('click', () => renderOpenApiPreview(openApiCatalog));
    previewForm.addEventListener('submit', async event => {
      event.preventDefault();
      if (previewButton.disabled) return;
      const file = fileInput.files[0];
      resetOpenApiPreview();
      previewButton.disabled = true;
      fileInput.disabled = true;
      try {
        const source = await readOpenApiFile(file);
        const preview = await api(previewUrl(), { method: 'POST', body: source });
        openApiSource = source;
        if (preview.selectionRequired) openApiCatalog = preview;
        renderOpenApiPreview(preview);
      } catch (error) {
        byId('openApiStatus').textContent = error.message;
      } finally {
        previewButton.disabled = false;
        fileInput.disabled = false;
      }
    });

    byId('openApiImportForm').addEventListener('submit', async event => {
      event.preventDefault();
      if (previewButton.disabled) return;
      const form = event.currentTarget;
      const operations = new FormData(form).getAll('operations');
      if (!openApiSource || operations.length === 0) {
        byId('openApiStatus').textContent = t('openapi.selectOperation');
        return;
      }
      if (operations.length > 200) {
        byId('openApiStatus').textContent = t('openapi.selectionLimit');
        return;
      }
      const validating = !byId('openApiCheck').hidden;
      const version = byId('openApiVersionField').hidden ? '' : versionInput.value.trim();
      const hasConflict = !validating && form.querySelector('input[data-version-conflict]:checked');
      if (hasConflict && (!version || version === openApiVersion)) {
        byId('openApiStatus').textContent = t('openapi.versionRequired');
        versionInput.setAttribute('aria-invalid', 'true');
        versionInput.focus();
        return;
      }
      const button = byId(validating ? 'openApiCheck' : 'openApiImport');
      button.disabled = true;
      previewButton.disabled = true;
      fileInput.disabled = true;
      byId('openApiBack').disabled = true;
      try {
        const body = { ...openApiSource, operations };
        if (validating) {
          const preview = await api(previewUrl(), { method: 'POST', body });
          openApiSelection = new Set(operations);
          renderOpenApiPreview(preview);
          return;
        }
        const result = await api(new URL(form.action).pathname, {
          method: 'POST', body: version ? { ...body, version } : body,
        });
        resetOpenApiPreview();
        byId('openApiStatus').textContent = t('openapi.importResult', {
          created: result.created.length, existing: result.existing.length,
        });
        await load();
      } catch (error) {
        byId('openApiStatus').textContent = error.message;
      } finally {
        button.disabled = false;
        previewButton.disabled = false;
        fileInput.disabled = false;
        byId('openApiBack').disabled = false;
      }
    });
  }

  function bindAsyncApi() {
    const fileInput = byId('asyncApiFile');
    const previewButton = byId('asyncApiPreview');
    const importForm = byId('asyncApiImportForm');
    const importButton = byId('asyncApiImport');
    const selection = byId('asyncApiSelection');
    const message = byId('asyncApiStatus');
    const operationsList = byId('asyncApiOperations');
    const selectionLimit = 200;
    let source;

    function resetPreview() {
      source = null;
      operationsList.replaceChildren();
      importForm.hidden = true;
      message.textContent = '';
    }

    fileInput.addEventListener('change', resetPreview);
    byId('asyncApiPreviewForm').addEventListener('submit', async event => {
      event.preventDefault();
      if (previewButton.disabled) return;
      resetPreview();
      previewButton.disabled = true;
      fileInput.disabled = true;
      try {
        const text = await fileInput.files[0].text();
        let parsed;
        try {
          parsed = JSON.parse(text);
        } catch {
          throw new Error(t('asyncapi.invalidFile'));
        }
        const operations = parsed?.operations;
        if (parsed?.asyncapi !== '3.0.0' || !operations || typeof operations !== 'object' || Array.isArray(operations)) {
          throw new Error(t('asyncapi.invalidDocument'));
        }
        const entries = Object.entries(operations);
        if (entries.length === 0) throw new Error(t('asyncapi.noOperations'));
        const fragment = document.createDocumentFragment();
        for (const [index, [name, operation]] of entries.entries()) {
          const row = document.createElement('div');
          row.className = 'button-row';
          const checkbox = document.createElement('input');
          checkbox.type = 'checkbox';
          checkbox.name = 'operations';
          checkbox.id = 'asyncapi-operation-' + index;
          checkbox.value = name;
          checkbox.checked = entries.length <= selectionLimit;
          const label = document.createElement('label');
          label.htmlFor = checkbox.id;
          const action = operation?.action;
          label.textContent = action === 'send' || action === 'receive' ? `${name} · ${t('asyncapi.' + action)}` : name;
          row.append(checkbox, label);
          fragment.append(row);
        }
        source = parsed;
        operationsList.replaceChildren(fragment);
        importForm.hidden = false;
        message.textContent = t(entries.length > selectionLimit ? 'openapi.selectionLimit' : 'asyncapi.previewResult',
          { count: entries.length });
        byId('asyncApiOperationsLabel').focus();
      } catch (error) {
        message.textContent = error.message;
      } finally {
        previewButton.disabled = false;
        fileInput.disabled = false;
      }
    });

    importForm.addEventListener('submit', async event => {
      event.preventDefault();
      if (!source || !service || importButton.disabled) return;
      const operations = new FormData(importForm).getAll('operations');
      if (operations.length === 0 || operations.length > selectionLimit) {
        message.textContent = t(operations.length === 0 ? 'openapi.selectOperation' : 'openapi.selectionLimit');
        return;
      }
      importButton.disabled = true;
      previewButton.disabled = true;
      fileInput.disabled = true;
      selection.disabled = true;
      try {
        const path = `${projectBase()}/services/${encodeURIComponent(serviceId)}/apis/asyncapi/import`;
        const result = await api(path, { method: 'POST', body: { document: source, operations } });
        resetPreview();
        fileInput.value = '';
        message.textContent = t('openapi.importResult', { created: result.created.length, existing: result.existing.length });
        byId('asyncApiHeading').focus();
        await load();
      } catch (error) {
        message.textContent = error.message;
      } finally {
        importButton.disabled = false;
        previewButton.disabled = false;
        fileInput.disabled = false;
        selection.disabled = false;
      }
    });
  }

  function bindEvents() {
    bindOpenApi();
    bindAsyncApi();
    byId('reloadService').addEventListener('click', load);
    byId('serviceForm').addEventListener('submit', async event => {
      event.preventDefault();
      if (!service) return;
      try {
        service = await api(`${projectBase()}/services/${encodeURIComponent(serviceId)}`, {
          method: 'PUT',
          body: {
            name: byId('serviceName').value.trim(),
            serviceType: byId('serviceType').value,
            description: byId('serviceDescription').value.trim() || null,
          },
        });
        renderService();
        status(t('service.saved'));
      } catch (error) { status(error.message, 'error'); }
    });
  }

  window.SDDP.onContextChange(() => {
    if (state.projectId !== initialProjectId) {
      location.href = projectUrl(state.projectId);
      return;
    }
    load();
  });

  window.SDDP.onReady(() => {
    bindEvents();
    load();
  });
})();
