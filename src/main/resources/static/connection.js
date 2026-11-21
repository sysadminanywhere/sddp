(() => {
  'use strict';
  const { state, esc, api, projectBase, projectUrl, status, requireProject, latestApis, flash, showFlash, environment,
    isProtectedEnvironment, refreshEnvironments } = window.SDDP;
  const { t } = window.I18N;

  const byId = id => document.getElementById(id);
  const params = new URLSearchParams(location.search);
  const connectionId = params.get('connectionId') || '';
  const requestedMode = params.get('mode') || '';
  const mode = ['create', 'edit', 'delete'].includes(requestedMode)
    ? requestedMode
    : (connectionId ? 'edit' : 'create');
  const initialProjectId = state.projectId;

  let project = null;
  let services = [];
  let apis = [];
  let environments = [];
  let connection = null;
  let connectionEnvironment = state.environment;
  let mappingRules = [];

  function serviceName(id) {
    return services.find(service => service.id === id)?.name || id;
  }

  function apiById(id) {
    return apis.find(item => item.id === id) || null;
  }

  function apisFor(serviceId) {
    return latestApis(apis).filter(item => item.serviceId === serviceId);
  }

  function apiLabel(item) {
    return item ? `${item.name} v${item.version}` : '';
  }

  function projectConnectionsUrl() {
    const url = new URL('/project.html', location.origin);
    url.searchParams.set('projectId', state.projectId);
    url.searchParams.set('tab', 'connections');
    return `${url.pathname}?${url.searchParams.toString()}`;
  }

  function renderBreadcrumbs() {
    byId('breadcrumbProject').textContent = project?.name || t('service.breadcrumbProject');
    byId('breadcrumbProject').href = projectUrl();
    const label = connection
      ? `${serviceName(connection.source)} → ${serviceName(connection.target)}`
      : t('connection.breadcrumb');
    byId('breadcrumbConnection').textContent = label;
  }

  function renderActions() {
    const toProject = `<a class="button-link secondary" href="${projectConnectionsUrl()}">${t('connection.toConnections')}</a>`;
    byId('formActions').innerHTML = toProject;
  }

  function renderChrome() {
    renderBreadcrumbs();
    renderActions();
    const titles = {
      create: t('project.connectionNew'),
      edit: t('project.connectionEdit'),
      delete: t('connection.mode.delete'),
    };
    const subtitles = {
      create: project ? t('connection.subtitle.create', { name: project.name, environment: connectionEnvironment }) : '',
      edit: connection ? t('connection.subtitle.edit', { environment: connectionEnvironment }) : '',
      delete: t('connection.subtitle.delete'),
    };
    byId('formTitle').textContent = titles[mode] || t('project.connectionNew');
    byId('formSubtitle').textContent = subtitles[mode] || '';
    document.title = `${titles[mode] || t('project.connectionNew')} · ${project?.name || t('service.breadcrumbProject')} · SDDP`;
  }

  function showPanel() {
    byId('formPanel').hidden = mode === 'delete';
    byId('deletePanel').hidden = mode !== 'delete';
  }

  function environmentOptionLabel(item) {
    return `${item.code}${item.name ? ` — ${item.name}` : ''}${item.isProtected ? ` (${t('common.protected')})` : ''}`;
  }

  function fillServiceSelect(select, selected) {
    select.innerHTML = services.map(service => `<option value="${esc(service.id)}">${esc(service.name)}</option>`).join('')
      || `<option value="">${t('common.noServices')}</option>`;
    if (selected && services.some(service => service.id === selected)) select.value = selected;
  }

  function fillApiSelect(select, serviceId, selected, emptyLabel) {
    const items = apisFor(serviceId);
    const options = items.map(item => `<option value="${esc(item.id)}">${esc(apiLabel(item))}</option>`);
    if (emptyLabel !== null) options.unshift(`<option value="">${esc(emptyLabel)}</option>`);
    const current = selected ? apiById(selected) : null;
    if (current && current.serviceId === serviceId && !items.some(item => item.id === selected)) {
      options.push(`<option value="${esc(current.id)}">${esc(apiLabel(current))} (${t('common.currentVersionSuffix')})</option>`);
    }
    select.innerHTML = options.join('') || `<option value="">${t('common.noApis')}</option>`;
    if (selected && (items.some(item => item.id === selected) || current?.serviceId === serviceId)) select.value = selected;
    else if (!selected && emptyLabel === null && items.length) select.value = items[0].id;
  }

  function updateProtectedNotice() {
    const code = connectionEnvironment;
    const protectedRow = byId('protectedConfirmRow');
    if (mode !== 'delete' && isProtectedEnvironment(code)) {
      protectedRow.hidden = false;
      byId('confirmProtectedText').textContent = t('connection.confirmProtected', { code });
    } else {
      protectedRow.hidden = true;
      byId('confirmProtected').checked = false;
    }
    const note = environment(code);
    byId('connectionEnvironment').value = note
      ? environmentOptionLabel(note)
      : code;
  }

  function renderEditor() {
    byId('editorTitle').textContent = mode === 'create' ? t('project.connectionNew') : t('project.connectionEdit');
    byId('formBadge').textContent = mode;
    byId('saveConnection').textContent = mode === 'create' ? t('connection.save.create') : t('connection.save.edit');
    byId('cancelConnection').href = projectConnectionsUrl();

    fillServiceSelect(byId('sourceService'), connection?.source || services[0]?.id);
    fillServiceSelect(byId('targetService'), connection?.target || services[1]?.id || services[0]?.id);
    fillApiSelect(byId('sourceApi'), byId('sourceService').value, connection?.sourceApiId || '', t('project.noSourceApiOption'));
    fillApiSelect(byId('targetApi'), byId('targetService').value, connection?.targetApiId || '', null);

    byId('endpoint').defaultValue = apiById(byId('targetApi').value)?.httpOperation?.path || '/';
    if (mode === 'create') {
      byId('transport').value = 'REST';
      byId('host').value = 'localhost';
      byId('port').value = '8080';
      byId('endpoint').value = byId('endpoint').defaultValue;
      byId('timeout').value = '5000';
      byId('retries').value = '3';
      byId('security').value = 'NONE';
      mappingRules = [];
    } else {
      const settings = connection.connectionSettings || {};
      byId('transport').value = connection.transportType;
      byId('host').value = settings.host ?? 'localhost';
      byId('port').value = settings.port ?? 8080;
      byId('endpoint').value = settings.target_endpoint ?? apiById(byId('targetApi').value)?.httpOperation?.path ?? '/';
      byId('timeout').value = settings.timeout_ms ?? 5000;
      byId('retries').value = settings.retries ?? 3;
      byId('security').value = settings.security_type ?? 'NONE';
      mappingRules = Array.isArray(connection.mappingRules?.transformations)
        ? connection.mappingRules.transformations.map(normalizeRule)
        : [];
    }
    updateProtectedNotice();
    renderMappingRules();
  }

  function renderDelete() {
    const current = environment(connectionEnvironment);
    byId('deleteSummary').innerHTML = `
      <div><span>${t('project.source')}</span><strong>${esc(serviceName(connection.source))}</strong></div>
      <div><span>${t('project.target')}</span><strong>${esc(serviceName(connection.target))}</strong></div>
      <div><span>${t('project.targetApi')}</span><strong>${esc(connection.targetApi || '—')}</strong></div>
      <div><span>${t('project.transport')}</span><strong>${esc(connection.transportType)}</strong></div>
      <div><span>${t('project.environment')}</span><strong>${esc(current ? environmentOptionLabel(current) : connectionEnvironment)}</strong></div>
      <div><span>${t('common.id')}</span><code>${esc(connection.id)}</code></div>`;
    byId('cancelDelete').href = projectConnectionsUrl();
    byId('confirmDelete').checked = false;
    const protectedRow = byId('protectedDeleteRow');
    if (isProtectedEnvironment(connectionEnvironment)) {
      protectedRow.hidden = false;
      byId('confirmProtectedDeleteText').textContent = t('connection.confirmProtected', { code: connectionEnvironment });
    } else {
      protectedRow.hidden = true;
      byId('confirmProtectedDelete').checked = false;
    }
  }

  function defaultStatus() {
    if (mode === 'create') return project ? t('connection.defaultStatus.create', { environment: connectionEnvironment }) : t('project.connectionNew');
    if (mode === 'edit') return t('connection.defaultStatus.edit', { environment: connectionEnvironment });
    return t('connection.defaultStatus.delete');
  }

  function renderFatal(message) {
    byId('formTitle').textContent = t('connection.fatalTitle');
    byId('formSubtitle').textContent = message;
    byId('formActions').innerHTML = `<a class="button-link secondary" href="${projectUrl()}">${t('connection.toProject')}</a>`;
    byId('formPanel').hidden = true;
    byId('deletePanel').hidden = true;
  }

  function protectedHeaders(code) {
    return isProtectedEnvironment(code) ? { 'X-SDDP-Confirm-Protected': 'true' } : {};
  }

  function normalizeRule(rule) {
    if (rule.type === 'CONCATENATE') {
      return { type: 'CONCATENATE', from: Array.isArray(rule.from) ? rule.from.slice() : [], to: rule.to || '', separator: rule.separator ?? ' ', value: '' };
    }
    if (rule.type === 'HARDCODED_VALUE') {
      return { type: 'HARDCODED_VALUE', from: [], to: rule.to || '', separator: ' ', value: JSON.stringify(rule.value ?? '') };
    }
    return { type: 'DIRECT_COPY', from: rule.from || '', to: rule.to || '', separator: ' ', value: '' };
  }

  function schemaFields(schema, prefix = '$') {
    const result = [];
    if (!schema || typeof schema !== 'object' || !schema.properties || typeof schema.properties !== 'object') return result;
    for (const [name, property] of Object.entries(schema.properties)) {
      const path = `${prefix}.${name}`;
      const type = property?.type || (property?.properties ? 'object' : property?.items ? 'array' : 'any');
      result.push({ path, label: `${path} · ${type}` });
      if (property?.type === 'object' || property?.properties) result.push(...schemaFields(property, path));
    }
    return result;
  }

  function sourceFields() {
    return schemaFields(apiById(byId('sourceApi').value)?.requestSchema);
  }

  function targetFields() {
    return schemaFields(apiById(byId('targetApi').value)?.requestSchema);
  }

  function fieldSelect(fields, value, multiple, onChange) {
    const select = document.createElement('select');
    select.className = 'mapping-field';
    if (multiple) select.multiple = true;
    if (!fields.length) {
      select.disabled = true;
      select.append(new Option(t('project.noRequestFields'), ''));
      return select;
    }
    if (!multiple) select.append(new Option(t('project.selectField'), ''));
    fields.forEach(field => select.append(new Option(field.label, field.path)));
    if (multiple) {
      const values = new Set(Array.isArray(value) ? value : []);
      [...select.options].forEach(option => { option.selected = values.has(option.value); });
      select.size = Math.min(Math.max(fields.length, 2), 5);
    } else {
      select.value = value || '';
    }
    select.addEventListener('change', () => {
      onChange(multiple ? [...select.selectedOptions].map(option => option.value) : select.value);
      updateMappingPreview();
    });
    return select;
  }

  function renderMappingRule(rule, index) {
    const row = document.createElement('div');
    row.className = 'mapping-rule';

    const type = document.createElement('select');
    type.setAttribute('aria-label', t('project.ruleType'));
    ['DIRECT_COPY', 'CONCATENATE', 'HARDCODED_VALUE'].forEach(value => type.append(new Option(value, value)));
    type.value = rule.type;
    type.addEventListener('change', () => {
      rule.type = type.value;
      renderMappingRules();
    });

    const source = document.createElement('div');
    source.className = 'mapping-source';
    if (rule.type === 'HARDCODED_VALUE') {
      const input = document.createElement('input');
      input.value = rule.value ?? '';
      input.placeholder = t('project.constantValuePlaceholder');
      input.setAttribute('aria-label', t('project.constantValueAria'));
      input.addEventListener('input', () => { rule.value = input.value; updateMappingPreview(); });
      source.append(input);
    } else {
      source.append(fieldSelect(sourceFields(), rule.from, rule.type === 'CONCATENATE',
        value => { rule.from = value; }));
    }

    const target = fieldSelect(targetFields(), rule.to, false, value => { rule.to = value; });

    const extra = document.createElement('div');
    extra.className = 'mapping-extra';
    if (rule.type === 'CONCATENATE') {
      const separator = document.createElement('input');
      separator.value = rule.separator ?? ' ';
      separator.placeholder = t('project.separator');
      separator.setAttribute('aria-label', t('project.separator'));
      separator.addEventListener('input', () => { rule.separator = separator.value; updateMappingPreview(); });
      extra.append(separator);
    } else {
      extra.append(Object.assign(document.createElement('span'), {
        className: 'mapping-hint',
        textContent: rule.type === 'HARDCODED_VALUE' ? t('project.constant') : t('project.copy'),
      }));
    }

    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'danger schema-small';
    remove.textContent = t('common.delete');
    remove.addEventListener('click', () => { mappingRules.splice(index, 1); renderMappingRules(); });

    row.append(type, source, target, extra, remove);
    return row;
  }

  function renderMappingRules() {
    const container = byId('mappingRules');
    container.replaceChildren();
    if (!mappingRules.length) {
      const empty = document.createElement('div');
      empty.className = 'empty-state';
      empty.textContent = t('project.noMappingRules');
      container.append(empty);
    } else {
      mappingRules.forEach((rule, index) => container.append(renderMappingRule(rule, index)));
    }
    updateMappingPreview();
  }

  function buildMapping() {
    if (!mappingRules.length) return null;
    const sourceApiLabel = apiLabel(apiById(byId('sourceApi').value));
    const targetApiLabel = apiLabel(apiById(byId('targetApi').value));
    const transformations = mappingRules.map(rule => {
      const to = String(rule.to || '').trim();
      if (!to.startsWith('$.')) throw new Error(t('project.needTargetField'));
      if (rule.type === 'DIRECT_COPY') {
        const from = String(rule.from || '').trim();
        if (!from.startsWith('$.')) throw new Error(t('project.needSourceDirect'));
        return { type: 'DIRECT_COPY', from, to };
      }
      if (rule.type === 'CONCATENATE') {
        const from = (rule.from || []).filter(Boolean);
        if (!from.length) throw new Error(t('project.needSourceConcat'));
        return { type: 'CONCATENATE', from, to, separator: rule.separator ?? ' ' };
      }
      if (rule.type === 'HARDCODED_VALUE') {
        let value;
        try { value = JSON.parse(rule.value); } catch { value = rule.value; }
        return { type: 'HARDCODED_VALUE', value, to };
      }
      throw new Error(t('project.unknownRuleType'));
    });
    const mapping = { target_api: targetApiLabel || '', transformations };
    if (sourceApiLabel) mapping.source_api = sourceApiLabel;
    return mapping;
  }

  function updateMappingPreview() {
    try {
      const mapping = buildMapping();
      byId('mappingPreview').textContent = mapping ? JSON.stringify(mapping, null, 2) : 'null';
    } catch (error) {
      byId('mappingPreview').textContent = `${t('project.mappingPreviewHint')}\n${error.message}`;
    }
  }

  function connectionSettings() {
    return {
      host: byId('host').value.trim(),
      port: Number(byId('port').value),
      target_endpoint: byId('endpoint').value.trim(),
      timeout_ms: Number(byId('timeout').value),
      retries: Number(byId('retries').value),
      security_type: byId('security').value,
    };
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

  async function load() {
    if (!requireProject()) {
      renderFatal(t('connection.fatalHint'));
      return;
    }
    try {
      status(t('connection.loading'));
      project = await api(projectBase());
      await refreshEnvironments();
      environments = state.environments;
      [services, apis] = await Promise.all([
        api(`${projectBase()}/services`),
        api(`${projectBase()}/apis`),
      ]);
      if (connectionId) {
        const detail = await api(`${projectBase()}/connections/${encodeURIComponent(connectionId)}`);
        connectionEnvironment = detail.environment || state.environment;
        connection = { ...detail.connection, environment: connectionEnvironment };
      }
      if (mode !== 'create' && !connection) throw new Error(t('connection.notFound'));
      renderChrome();
      showPanel();
      if (mode === 'delete') renderDelete();
      else renderEditor();
      if (!showFlash()) status(defaultStatus());
    } catch (error) {
      status(error.message, 'error');
      renderFatal(error.message);
    }
  }

  function updateTargetMapping() {
    const target = apiById(byId('targetApi').value);
    if (byId('transport').value === 'REST') {
      const endpoint = byId('endpoint');
      const replace = !endpoint.value.trim() || endpoint.value === endpoint.defaultValue;
      endpoint.defaultValue = target?.httpOperation?.path || '/';
      if (replace) endpoint.value = endpoint.defaultValue;
    }
    renderMappingRules();
  }

  function bindEvents() {
    bindJsonToggles();
    byId('sourceService').addEventListener('change', () => {
      fillApiSelect(byId('sourceApi'), byId('sourceService').value, '', t('project.noSourceApiOption'));
      renderMappingRules();
    });
    byId('targetService').addEventListener('change', () => {
      fillApiSelect(byId('targetApi'), byId('targetService').value, '', null);
      updateTargetMapping();
    });
    byId('sourceApi').addEventListener('change', renderMappingRules);
    byId('targetApi').addEventListener('change', updateTargetMapping);
    byId('addMappingRule').addEventListener('click', () => {
      mappingRules.push({ type: 'DIRECT_COPY', from: '', to: '', separator: ' ', value: '' });
      renderMappingRules();
    });
    byId('clearMapping').addEventListener('click', () => {
      mappingRules = [];
      renderMappingRules();
      status(t('project.mappingCleared'), 'warning');
    });

    byId('connectionForm').addEventListener('submit', async event => {
      event.preventDefault();
      if (!project) return;
      if (!byId('sourceService').value || !byId('targetService').value) {
        status(t('project.addServicesFirst'), 'warning');
        return;
      }
      if (byId('sourceService').value === byId('targetService').value) {
        status(t('project.sameService'), 'warning');
        return;
      }
      if (!byId('targetApi').value) {
        status(t('project.selectTargetApi'), 'warning');
        return;
      }
      if (isProtectedEnvironment(connectionEnvironment) && !byId('confirmProtected').checked) {
        status(t('connection.confirmRequired', { code: connectionEnvironment }), 'warning');
        return;
      }
      try {
        const body = {
          environment: connectionEnvironment,
          sourceServiceId: byId('sourceService').value,
          targetServiceId: byId('targetService').value,
          sourceApiId: byId('sourceApi').value || null,
          targetApiId: byId('targetApi').value,
          transportType: byId('transport').value,
          connectionSettings: connectionSettings(),
          mappingRules: buildMapping(),
        };
        const headers = protectedHeaders(connectionEnvironment);
        if (mode === 'edit') {
          await api(`${projectBase()}/connections/${encodeURIComponent(connectionId)}`, { method: 'PUT', body, headers });
          flash(t('connection.flashUpdated'));
        } else {
          await api(`${projectBase()}/connections`, { method: 'POST', body, headers });
          flash(t('connection.flashCreated'));
        }
        location.href = projectConnectionsUrl();
      } catch (error) { status(error.message, 'error'); }
    });

    byId('deleteForm').addEventListener('submit', async event => {
      event.preventDefault();
      if (!byId('confirmDelete').checked) {
        status(t('connection.confirmRequired'), 'warning');
        return;
      }
      if (isProtectedEnvironment(connectionEnvironment) && !byId('confirmProtectedDelete').checked) {
        status(t('connection.confirmRequired', { code: connectionEnvironment }), 'warning');
        return;
      }
      try {
        await api(`${projectBase()}/connections/${encodeURIComponent(connectionId)}`,
          { method: 'DELETE', headers: protectedHeaders(connectionEnvironment) });
        flash(t('connection.flashDeleted'));
        location.href = projectConnectionsUrl();
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
