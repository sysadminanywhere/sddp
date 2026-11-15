(() => {
  'use strict';
  const { state, esc, api, projectBase, projectUrl, serviceUrl, apiUrl, status, requireProject, nextVersion, fmtDate, flash, showFlash } = window.SDDP;
  const { t } = window.I18N;

  const byId = id => document.getElementById(id);
  const params = new URLSearchParams(location.search);
  const serviceId = params.get('serviceId') || '';
  const apiId = params.get('apiId') || '';
  const requestedMode = params.get('mode') || '';
  const mode = ['create', 'edit', 'version', 'delete', 'view'].includes(requestedMode)
    ? requestedMode
    : (apiId ? 'view' : 'create');
  const initialProjectId = state.projectId;

  let project = null;
  let service = null;
  let item = null;
  let requestEditor = null;
  let responseEditor = null;

  function emptySchema() {
    return { type: 'object', properties: {} };
  }

  function apiLabel(value) {
    return value ? `${value.name} v${value.version}` : '';
  }

  function viewUrl() {
    return apiUrl(serviceId, apiId, 'view', state.projectId);
  }

  function editUrl() {
    return apiUrl(serviceId, apiId, 'edit', state.projectId);
  }

  function versionUrl() {
    return apiUrl(serviceId, apiId, 'version', state.projectId);
  }

  function deleteUrl() {
    return apiUrl(serviceId, apiId, 'delete', state.projectId);
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

  function renderSchemaFields(schema) {
    const fields = schemaFieldList(schema);
    if (!fields.length) return `<p class="hint">${t('api.noFields')}</p>`;
    return `<ul class="field-list">${fields.map(field => `
      <li style="--depth:${field.depth}">
        <code>${esc(field.label)}</code>
        <span class="field-type">${esc(field.type)}</span>
        ${field.required ? `<span class="badge warn">${t('schema.required')}</span>` : ''}
      </li>`).join('')}</ul>`;
  }

  function updatePreview(kind) {
    const editor = kind === 'request' ? requestEditor : responseEditor;
    if (!editor) return;
    const target = kind === 'request' ? byId('requestSchemaPreview') : byId('responseSchemaPreview');
    target.textContent = JSON.stringify(editor.toSchema(), null, 2);
  }

  function renderBreadcrumbs() {
    byId('breadcrumbProject').textContent = project?.name || t('service.breadcrumbProject');
    byId('breadcrumbProject').href = projectUrl();
    byId('breadcrumbService').textContent = service?.name || t('service.breadcrumbService');
    byId('breadcrumbService').href = serviceUrl(serviceId);
    byId('breadcrumbApi').textContent = mode === 'create' ? t('api.mode.create') : apiLabel(item) || t('api.breadcrumb');
  }

  function renderActions() {
    const toService = `<a class="button-link secondary" href="${serviceUrl(serviceId)}">${t('api.toService')}</a>`;
    if (mode === 'view') {
      byId('apiActions').innerHTML = `
        <a class="button-link" href="${editUrl()}">${t('api.edit')}</a>
        <a class="button-link secondary" href="${versionUrl()}">${t('api.newVersion')}</a>
        <a class="button-link danger" href="${deleteUrl()}">${t('api.deleteAction')}</a>
        ${toService}`;
      return;
    }
    if (mode === 'create') {
      byId('apiActions').innerHTML = toService;
      return;
    }
    byId('apiActions').innerHTML = `
      <a class="button-link secondary" href="${viewUrl()}">${t('api.toOperation')}</a>
      ${toService}`;
  }

  function renderChrome() {
    renderBreadcrumbs();
    renderActions();
    const titles = {
      create: t('api.mode.create'),
      view: item ? t('api.mode.viewNamed', { name: item.name, version: item.version }) : t('api.mode.view'),
      edit: item ? t('api.mode.editNamed', { name: item.name, version: item.version }) : t('api.mode.edit'),
      version: item ? t('api.mode.versionNamed', { name: item.name }) : t('api.mode.version'),
      delete: item ? t('api.mode.deleteNamed', { name: item.name, version: item.version }) : t('api.mode.delete'),
    };
    const subtitles = {
      create: service ? t('api.subtitle.create', { name: service.name }) : t('api.subtitle.createNoService'),
      view: item ? t('api.subtitle.view', { pattern: item.pattern, date: fmtDate(item.createdAt) }) : '',
      edit: t('api.subtitle.edit'),
      version: item ? t('api.subtitle.version', { version: item.version }) : '',
      delete: t('api.subtitle.delete'),
    };
    byId('apiTitle').textContent = titles[mode] || t('api.mode.view');
    byId('apiSubtitle').textContent = subtitles[mode] || '';
    document.title = `${titles[mode] || t('api.mode.view')} · ${project?.name || t('service.breadcrumbProject')} · SDDP`;
  }

  function showPanel() {
    byId('viewPanel').hidden = mode !== 'view';
    byId('formPanel').hidden = !['create', 'edit', 'version'].includes(mode);
    byId('deletePanel').hidden = mode !== 'delete';
  }

  function renderView() {
    byId('viewVersion').textContent = `v${item.version}`;
    byId('viewDescription').textContent = item.description || t('api.noDescription');
    byId('viewPattern').textContent = item.pattern;
    byId('viewCreated').textContent = fmtDate(item.createdAt);
    byId('viewId').textContent = item.id;
    byId('viewRequest').innerHTML = renderSchemaFields(item.requestSchema);
    byId('viewResponse').innerHTML = renderSchemaFields(item.responseSchema);
    byId('viewRequestJson').textContent = JSON.stringify(item.requestSchema ?? {}, null, 2);
    byId('viewResponseJson').textContent = JSON.stringify(item.responseSchema ?? {}, null, 2);
  }

  function renderForm() {
    const formTitles = {
      create: t('api.mode.create'),
      edit: item ? t('api.mode.editNamed', { name: item.name, version: item.version }) : t('api.mode.edit'),
      version: item ? t('api.mode.versionNamed', { name: item.name }) : t('api.mode.version'),
    };
    byId('apiFormTitle').textContent = formTitles[mode];
    byId('apiFormBadge').textContent = mode;
    byId('saveApi').textContent = mode === 'create' ? t('api.save.create') : mode === 'version' ? t('api.save.version') : t('api.save.edit');
    byId('cancelApi').href = mode === 'create' ? serviceUrl(serviceId) : viewUrl();

    if (mode === 'create') {
      byId('apiName').value = '';
      byId('apiVersion').value = '1';
      byId('apiPattern').value = 'REQUEST_RESPONSE';
      byId('apiDescription').value = '';
      requestEditor.setSchema(emptySchema());
      responseEditor.setSchema(emptySchema());
    } else {
      byId('apiName').value = item.name;
      byId('apiVersion').value = mode === 'version' ? nextVersion(item.version) : item.version;
      byId('apiPattern').value = item.pattern;
      byId('apiDescription').value = item.description || '';
      requestEditor.setSchema(item.requestSchema || emptySchema());
      responseEditor.setSchema(item.responseSchema || emptySchema());
    }
    byId('apiName').disabled = mode === 'version';
    byId('apiPattern').disabled = mode === 'version';
    updatePreview('request');
    updatePreview('response');
    if (mode === 'version') byId('apiVersion').focus();
    else if (mode === 'edit') byId('apiName').focus();
  }

  function renderDelete() {
    byId('deleteVersion').textContent = `v${item.version}`;
    byId('deleteSummary').innerHTML = `
      <div><span>${t('common.name')}</span><strong>${esc(item.name)}</strong></div>
      <div><span>${t('common.version')}</span><strong>${esc(item.version)}</strong></div>
      <div><span>${t('common.pattern')}</span><strong>${esc(item.pattern)}</strong></div>
      <div><span>${t('common.id')}</span><code>${esc(item.id)}</code></div>`;
    byId('cancelDelete').href = viewUrl();
    byId('confirmDelete').checked = false;
  }

  function defaultStatus() {
    if (mode === 'create') return service ? t('api.defaultStatus.create', { name: service.name }) : t('api.defaultStatus.createNoService');
    if (mode === 'edit') return t('api.defaultStatus.edit', { name: item.name, version: item.version });
    if (mode === 'version') return t('api.defaultStatus.version', { name: item.name });
    if (mode === 'delete') return t('api.defaultStatus.delete', { name: item.name, version: item.version });
    return t('api.defaultStatus.view', { name: item.name, version: item.version });
  }

  function renderFatal(message) {
    byId('apiTitle').textContent = t('api.fatalTitle');
    byId('apiSubtitle').textContent = message;
    byId('apiActions').innerHTML = `<a class="button-link secondary" href="${serviceId ? serviceUrl(serviceId) : '/index.html'}">${t('api.toService')}</a>`;
    byId('viewPanel').hidden = true;
    byId('formPanel').hidden = true;
    byId('deletePanel').hidden = true;
  }

  async function load() {
    if (!requireProject() || !serviceId) {
      renderFatal(t('api.fatalHint'));
      return;
    }
    try {
      status(t('api.loadingApi'));
      [project, service] = await Promise.all([
        api(projectBase()),
        api(`${projectBase()}/services/${encodeURIComponent(serviceId)}`),
      ]);
      if (apiId) {
        item = await api(`${projectBase()}/services/${encodeURIComponent(serviceId)}/apis/${encodeURIComponent(apiId)}`);
      }
      if (mode !== 'create' && !item) throw new Error(t('api.notFound'));
      renderChrome();
      showPanel();
      if (mode === 'view') renderView();
      else if (mode === 'delete') renderDelete();
      else renderForm();
      if (!showFlash()) status(defaultStatus());
    } catch (error) {
      status(error.message, 'error');
      renderFatal(error.message);
    }
  }

  function bindJsonToggles() {
    document.addEventListener('click', event => {
      const toggle = event.target.closest('[data-json-toggle]');
      if (!toggle) return;
      const target = byId(toggle.dataset.jsonToggle);
      if (!target) return;
      const show = target.hidden;
      target.hidden = !show;
      toggle.textContent = show ? t('common.hideJson') : t('common.showJson');
    });
  }

  function bindEvents() {
    bindJsonToggles();
    byId('addRequestField').addEventListener('click', () => requestEditor.addField());
    byId('addResponseField').addEventListener('click', () => responseEditor.addField());

    byId('apiForm').addEventListener('submit', async event => {
      event.preventDefault();
      if (!service) return;
      try {
        const name = byId('apiName').value.trim();
        const version = byId('apiVersion').value.trim();
        const description = byId('apiDescription').value.trim() || null;
        const pattern = byId('apiPattern').value;
        const requestSchema = requestEditor.toSchema();
        const responseSchema = responseEditor.toSchema();
        const base = `${projectBase()}/services/${encodeURIComponent(serviceId)}/apis`;
        let saved;
        if (mode === 'edit') {
          saved = await api(`${base}/${encodeURIComponent(apiId)}`, {
            method: 'PUT',
            body: { name, version, description, pattern, requestSchema, responseSchema },
          });
          flash(t('api.flashSaved', { name: saved.name, version: saved.version }));
        } else if (mode === 'version') {
          saved = await api(`${base}/${encodeURIComponent(apiId)}/versions`, {
            method: 'POST',
            body: { version, description, pattern, requestSchema, responseSchema },
          });
          flash(t('api.flashVersionCreated', { name: saved.name, version: saved.version }));
        } else {
          saved = await api(base, {
            method: 'POST',
            body: { name, version, description, pattern, requestSchema, responseSchema },
          });
          flash(t('api.flashCreated', { name: saved.name, version: saved.version }));
        }
        location.href = apiUrl(serviceId, saved.id, 'view', state.projectId);
      } catch (error) { status(error.message, 'error'); }
    });

    byId('deleteForm').addEventListener('submit', async event => {
      event.preventDefault();
      if (!byId('confirmDelete').checked) {
        status(t('api.confirmRequired'), 'warning');
        return;
      }
      try {
        await api(`${projectBase()}/services/${encodeURIComponent(serviceId)}/apis/${encodeURIComponent(apiId)}`, { method: 'DELETE' });
        flash(t('api.flashDeleted', { name: item.name, version: item.version }));
        location.href = serviceUrl(serviceId);
      } catch (error) {
        const message = error.message.includes('used by')
          ? t('api.usedByConnections')
          : error.message;
        status(message, 'error');
      }
    });
  }

  window.SDDP.onContextChange(() => {
    if (state.projectId !== initialProjectId) {
      location.href = projectUrl(state.projectId);
    }
  });

  window.SDDP.onReady(() => {
    requestEditor = new window.SchemaEditor(byId('requestSchemaEditor'), { onChange: () => updatePreview('request') });
    responseEditor = new window.SchemaEditor(byId('responseSchemaEditor'), { onChange: () => updatePreview('response') });
    bindEvents();
    load();
  });
})();
