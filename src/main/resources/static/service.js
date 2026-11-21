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
  let openApiDocument = null;

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
      return `
      <article class="api-card">
        <header>
          <div>
            <h3><a class="api-title-link" href="${viewUrl}">${esc(item.name)}</a> <span class="badge accent">v${esc(item.version)}</span></h3>
            <small>${esc(item.httpOperation ? `${item.httpOperation.method} ${item.httpOperation.path}` : item.pattern)} · request: ${fieldCount(item.requestSchema)} · response: ${fieldCount(item.responseSchema)}</small>
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
    openApiDocument = null;
    byId('openApiImportForm').hidden = true;
    byId('openApiWarnings').hidden = true;
    byId('openApiStatus').textContent = '';
  }

  function renderOpenApiPreview(preview) {
    const warnings = byId('openApiWarnings');
    warnings.replaceChildren(...preview.warnings.map(warning => {
      const item = document.createElement('li');
      item.textContent = warning;
      return item;
    }));
    warnings.hidden = preview.warnings.length === 0;
    byId('openApiStatus').textContent = t('openapi.previewResult', {
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
      checkbox.checked = !operation.exists;
      label.htmlFor = checkbox.id;
      label.textContent = operation.method + ' ' + operation.path + ' · ' + operation.name
        + (operation.exists ? ' · ' + t('openapi.existing') : '');
      row.className = 'button-row';
      row.append(checkbox, label);
      return row;
    }));
    byId('openApiImportForm').hidden = preview.operations.length === 0;
  }

  function bindOpenApi() {
    const fileInput = byId('openApiFile');
    fileInput.addEventListener('change', resetOpenApiPreview);
    byId('openApiPreviewForm').addEventListener('submit', async event => {
      event.preventDefault();
      const form = event.currentTarget;
      const file = fileInput.files[0];
      const button = byId('openApiPreview');
      resetOpenApiPreview();
      button.disabled = true;
      try {
        let document;
        try {
          document = JSON.parse(await file.text());
        } catch {
          throw new Error(t('openapi.invalidFile'));
        }
        const preview = await api(new URL(form.action).pathname, { method: 'POST', body: { document } });
        if (fileInput.files[0] !== file) return;
        openApiDocument = document;
        renderOpenApiPreview(preview);
      } catch (error) {
        if (fileInput.files[0] === file) byId('openApiStatus').textContent = error.message;
      } finally {
        button.disabled = false;
      }
    });

    byId('openApiImportForm').addEventListener('submit', async event => {
      event.preventDefault();
      const form = event.currentTarget;
      const operations = new FormData(form).getAll('operations');
      if (!openApiDocument || operations.length === 0) {
        byId('openApiStatus').textContent = t('openapi.selectOperation');
        return;
      }
      const button = byId('openApiImport');
      button.disabled = true;
      fileInput.disabled = true;
      try {
        const result = await api(new URL(form.action).pathname, {
          method: 'POST', body: { document: openApiDocument, operations },
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
        fileInput.disabled = false;
      }
    });
  }

  function bindEvents() {
    bindOpenApi();
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
