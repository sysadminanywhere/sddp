(() => {
  'use strict';
  const { state, esc, api, projectBase, projectUrl, connectionUrl, status, requireProject, showFlash, latestApis,
    environment, isProtectedEnvironment, refreshEnvironments } = window.SDDP;
  const { t } = window.I18N;

  const byId = id => document.getElementById(id);

  let project = null;
  let services = [];
  let apis = [];
  let edges = [];
  let issuesByEdge = new Map();
  let selectedEdgeId = null;

  function projectConnectionsUrl() {
    const url = new URL('/project.html', location.origin);
    url.searchParams.set('projectId', state.projectId);
    url.searchParams.set('tab', 'connections');
    return `${url.pathname}?${url.searchParams.toString()}`;
  }

  function serviceName(id) {
    return services.find(service => service.id === id)?.name || id;
  }

  function apiById(id) {
    return apis.find(item => item.id === id) || null;
  }

  function apisForService(serviceId) {
    return apis.filter(item => item.serviceId === serviceId);
  }

  function apiLabel(item) {
    return item ? `${item.name} v${item.version}` : '';
  }

  // ---------- schema validation ----------

  function schemaFields(schema, prefix = '$', parentRequired = true, out = []) {
    if (!schema || typeof schema !== 'object') return out;
    const required = Array.isArray(schema.required) ? schema.required : [];
    const properties = schema.properties;
    if (!properties || typeof properties !== 'object') return out;
    for (const [name, property] of Object.entries(properties)) {
      const path = `${prefix}.${name}`;
      const isRequired = parentRequired && required.includes(name);
      out.push({ path, required: isRequired });
      if (property && (property.type === 'object' || property.properties)) {
        schemaFields(property, path, isRequired, out);
      }
    }
    return out;
  }

  function edgeIssues(edge) {
    const issues = [];
    const targetApi = apiById(edge.targetApiId);
    if (!targetApi) {
      issues.push({ kind: 'error', key: 'graph.issueNoTargetApi' });
      return { issues, kind: 'error' };
    }
    const sourceApi = edge.sourceApiId ? apiById(edge.sourceApiId) : null;
    const targetFields = schemaFields(targetApi.requestSchema);
    const sourcePaths = new Set(sourceApi ? schemaFields(sourceApi.requestSchema).map(field => field.path) : []);
    const rules = Array.isArray(edge.mappingRules?.transformations) ? edge.mappingRules.transformations : [];
    const coveredTo = new Set(rules.map(rule => String(rule.to || '').trim()).filter(Boolean));

    const missing = targetFields.filter(field => field.required && !coveredTo.has(field.path) && !sourcePaths.has(field.path));
    if (missing.length) {
      issues.push({
        kind: 'error',
        key: 'graph.issueMissingFields',
        params: { fields: missing.map(field => field.path).join(', ') },
      });
    }

    for (const rule of rules) {
      const to = String(rule.to || '').trim();
      if (to && !targetFields.some(field => field.path === to)) {
        issues.push({ kind: 'error', key: 'graph.issueUnknownTargetField', params: { field: to } });
      }
      if (rule.type !== 'HARDCODED_VALUE' && sourceApi) {
        const froms = Array.isArray(rule.from) ? rule.from : [rule.from];
        for (const from of froms) {
          const path = String(from || '').trim();
          if (path && !sourcePaths.has(path)) {
            issues.push({ kind: 'error', key: 'graph.issueUnknownSourceField', params: { field: path } });
          }
        }
      }
    }

    if (!rules.length && targetFields.length) {
      issues.push({ kind: 'warn', key: 'graph.issueNoMapping' });
    }

    const kind = issues.some(issue => issue.kind === 'error') ? 'error' : (issues.length ? 'warn' : 'ok');
    return { issues, kind };
  }

  function validateEdges() {
    issuesByEdge = new Map();
    for (const edge of edges) {
      issuesByEdge.set(edge.id, edgeIssues(edge));
    }
  }

  function edgeKind(edgeId) {
    return issuesByEdge.get(edgeId)?.kind || 'ok';
  }

  function issueText(issue) {
    return t(issue.key, issue.params || {});
  }

  // ---------- graph rendering ----------

  function dispatchGraph() {
    validateEdges();
    window.dispatchEvent(new CustomEvent('sddp:graph-data', {
      detail: {
        nodes: services.map(service => ({
          id: service.id,
          label: `${service.name} · ${service.serviceType}`,
          kind: String(service.serviceType || 'CORE').toLowerCase(),
          position: service.positionX != null && service.positionY != null
            ? { x: service.positionX, y: service.positionY }
            : undefined,
        })),
        edges: edges.map(edge => ({
          id: edge.id,
          source: edge.source,
          target: edge.target,
          label: edge.targetApi || t('graph.noTargetApi'),
          kind: edgeKind(edge.id),
          selected: edge.id === selectedEdgeId,
        })),
      },
    }));
    byId('issueCount').textContent = String([...issuesByEdge.values()].reduce((total, entry) => total + entry.issues.length, 0));
    renderIssues();
    if (selectedEdgeId) renderSelected(); else renderSelectedEmpty();
  }

  function dispatchEdges() {
    window.dispatchEvent(new CustomEvent('sddp:graph-edges', {
      detail: edges.map(edge => ({
        id: edge.id,
        source: edge.source,
        target: edge.target,
        label: edge.targetApi || t('graph.noTargetApi'),
        kind: edgeKind(edge.id),
        selected: edge.id === selectedEdgeId,
      })),
    }));
  }

  function renderLegend() {
    byId('graphLegend').innerHTML = ['ok', 'warn', 'error'].map(kind => `
      <span class="legend-item"><i class="legend-dot legend-${kind}"></i>${t(`graph.legend${kind.charAt(0).toUpperCase()}${kind.slice(1)}`)}</span>`).join('');
  }

  function renderIssues() {
    const items = [];
    for (const edge of edges) {
      const entry = issuesByEdge.get(edge.id);
      if (!entry?.issues.length) continue;
      for (const issue of entry.issues) {
        items.push(`
          <button type="button" class="issue-row ${issue.kind}" data-edge="${esc(edge.id)}">
            <span class="badge ${issue.kind === 'error' ? 'warn' : 'accent'}">${issue.kind === 'error' ? t('graph.legendError') : t('graph.legendWarn')}</span>
            <span class="issue-main">
              <strong>${esc(serviceName(edge.source))} → ${esc(serviceName(edge.target))}</strong>
              <small>${esc(issueText(issue))}</small>
            </span>
          </button>`);
      }
    }
    byId('issueList').innerHTML = items.length
      ? items.join('')
      : `<div class="empty-state">${t('graph.issuesEmpty')}</div>`;
  }

  function renderSelectedEmpty() {
    byId('selectedBadge').textContent = '—';
    byId('selectedConnection').innerHTML = `<div class="empty-state">${t('graph.selectedHint')}</div>`;
  }

  function renderSelected() {
    const edge = edges.find(item => item.id === selectedEdgeId);
    if (!edge) {
      renderSelectedEmpty();
      return;
    }
    const entry = issuesByEdge.get(edge.id);
    byId('selectedBadge').textContent = t(`graph.legend${edgeKind(edge.id).charAt(0).toUpperCase()}${edgeKind(edge.id).slice(1)}`);
    const issueLines = entry?.issues.length
      ? entry.issues.map(issue => `<small class="issue-line ${issue.kind}">${esc(issueText(issue))}</small>`).join('')
      : `<small class="issue-line ok">${esc(t('graph.noIssues'))}</small>`;
    byId('selectedConnection').innerHTML = `
      <div class="detail-grid">
        <div><span>${t('project.source')}</span><strong>${esc(serviceName(edge.source))}</strong></div>
        <div><span>${t('project.target')}</span><strong>${esc(serviceName(edge.target))}</strong></div>
        <div><span>${t('project.sourceApi')}</span><strong>${esc(edge.sourceApi || t('project.noSourceApi'))}</strong></div>
        <div><span>${t('project.targetApi')}</span><strong>${esc(edge.targetApi || t('project.noTargetApi'))}</strong></div>
        <div><span>${t('project.transport')}</span><strong>${esc(edge.transportType)}</strong></div>
        <div><span>${t('project.fieldMapping')}</span><strong>${edge.mappingRules ? t('project.mappingConfigured') : t('project.noMapping')}</strong></div>
      </div>
      <div class="selected-issues section-gap">${issueLines}</div>
      <div class="button-row section-gap">
        <a class="button-link secondary" href="${connectionUrl(edge.id, 'edit')}">${t('common.edit')}</a>
        <a class="button-link danger" href="${connectionUrl(edge.id, 'delete')}">${t('common.delete')}</a>
      </div>`;
  }

  function selectEdge(edgeId) {
    selectedEdgeId = edgeId;
    dispatchEdges();
    if (edgeId) renderSelected(); else renderSelectedEmpty();
  }

  // ---------- create-connection dialog ----------

  function fillApiSelect(select, serviceId, emptyLabel) {
    const items = apisForService(serviceId);
    const options = items.map(item => `<option value="${esc(item.id)}">${esc(apiLabel(item))}</option>`);
    if (emptyLabel !== null) options.unshift(`<option value="">${esc(emptyLabel)}</option>`);
    select.innerHTML = options.join('') || `<option value="">${t('common.noApis')}</option>`;
  }

  function openConnectDialog(detail) {
    const source = services.find(service => service.id === detail.source);
    const target = services.find(service => service.id === detail.target);
    if (!source || !target) return;
    if (source.id === target.id) {
      status(t('graph.connectSelfLoop'), 'warning');
      return;
    }
    byId('connectSource').value = source.name;
    byId('connectTarget').value = target.name;
    fillApiSelect(byId('connectSourceApi'), source.id, t('project.noSourceApiOption'));
    fillApiSelect(byId('connectTargetApi'), target.id, null);
    byId('connectTransport').value = 'REST';
    byId('connectEndpoint').value = '';
    suggestEndpoint();
    byId('connectError').hidden = true;
    byId('connectError').textContent = '';
    const protectedRow = byId('connectProtectedRow');
    if (isProtectedEnvironment()) {
      protectedRow.hidden = false;
      byId('connectProtectedText').textContent = t('connection.confirmProtected', { code: state.environment });
      byId('connectProtected').checked = false;
    } else {
      protectedRow.hidden = true;
      byId('connectProtected').checked = false;
    }
    byId('connectOverlay').dataset.source = source.id;
    byId('connectOverlay').dataset.target = target.id;
    byId('connectOverlay').hidden = false;
    byId('connectTargetApi').focus();
  }

  function suggestEndpoint() {
    const target = apiById(byId('connectTargetApi').value);
    const endpoint = byId('connectEndpoint');
    const replace = !endpoint.value.trim() || endpoint.value === endpoint.defaultValue;
    endpoint.defaultValue = byId('connectTransport').value === 'REST' ? target?.httpOperation?.path || '/' : '/';
    if (replace) endpoint.value = endpoint.defaultValue;
  }

  function closeConnectDialog() {
    byId('connectOverlay').hidden = true;
  }

  function bindDialogEvents() {
    byId('connectTargetApi').addEventListener('change', suggestEndpoint);
    byId('connectTransport').addEventListener('change', suggestEndpoint);
    byId('connectCancel').addEventListener('click', closeConnectDialog);
    byId('connectOverlay').addEventListener('click', event => {
      if (event.target === byId('connectOverlay')) closeConnectDialog();
    });
    document.addEventListener('keydown', event => {
      if (event.key === 'Escape' && !byId('connectOverlay').hidden) closeConnectDialog();
    });
    byId('connectForm').addEventListener('submit', async event => {
      event.preventDefault();
      const overlay = byId('connectOverlay');
      const sourceServiceId = overlay.dataset.source;
      const targetServiceId = overlay.dataset.target;
      const targetApiId = byId('connectTargetApi').value;
      const error = byId('connectError');
      const fail = message => {
        error.textContent = message;
        error.hidden = false;
      };
      if (!targetApiId) {
        fail(t('project.selectTargetApi'));
        return;
      }
      if (isProtectedEnvironment() && !byId('connectProtected').checked) {
        fail(t('connection.confirmRequired', { code: state.environment }));
        return;
      }
      try {
        await api(`${projectBase()}/connections`, {
          method: 'POST',
          headers: isProtectedEnvironment() ? { 'X-SDDP-Confirm-Protected': 'true' } : {},
          body: {
            environment: state.environment,
            sourceServiceId,
            targetServiceId,
            sourceApiId: byId('connectSourceApi').value || null,
            targetApiId,
            transportType: byId('connectTransport').value,
            connectionSettings: {
              host: 'localhost',
              port: 8080,
              target_endpoint: byId('connectEndpoint').value.trim() || '/',
              timeout_ms: 5000,
              retries: 3,
              security_type: 'NONE',
            },
            mappingRules: null,
          },
        });
        closeConnectDialog();
        status(t('connection.flashCreated'), 'info');
        await loadTopology();
      } catch (requestError) {
        fail(requestError.message);
      }
    });
  }

  // ---------- loading ----------

  async function loadTopology() {
    if (!requireProject()) return;
    try {
      status(t('graph.loading'));
      project = await api(projectBase());
      await refreshEnvironments();
      const [projectServices, allApis, topology] = await Promise.all([
        api(`${projectBase()}/services`),
        api(`${projectBase()}/apis`),
        api(`${projectBase()}/topology?environment=${encodeURIComponent(state.environment)}`),
      ]);
      services = projectServices;
      apis = latestApis(allApis);
      edges = topology.edges || [];
      selectedEdgeId = null;
      const current = environment();
      byId('graphEnvironmentNote').textContent = current
        ? t('graph.environmentNote', { code: current.code, protected: current.isProtected ? ` · ${t('common.protected')}` : '' })
        : '';
      byId('breadcrumbProject').textContent = project?.name || t('service.breadcrumbProject');
      document.title = `${t('graph.heading')} · ${project?.name || t('service.breadcrumbProject')} · SDDP`;
      dispatchGraph();
      status(t('graph.loaded', { count: edges.length, environment: state.environment }));
    } catch (error) {
      status(error.message, 'error');
    }
  }

  function bindEvents() {
    byId('reloadGraph').addEventListener('click', loadTopology);
    byId('toConnections').href = projectConnectionsUrl();
    byId('breadcrumbProject').href = projectUrl();
    byId('issueList').addEventListener('click', event => {
      const row = event.target.closest('[data-edge]');
      if (row) selectEdge(row.dataset.edge);
    });
    const autoLayout = () => window.dispatchEvent(new CustomEvent('sddp:graph-auto-layout', {
      detail: { direction: byId('layoutDirection').value },
    }));
    byId('autoLayout').addEventListener('click', autoLayout);
    byId('layoutDirection').addEventListener('change', autoLayout);
    byId('fitView').addEventListener('click', () => window.dispatchEvent(new CustomEvent('sddp:graph-fit-view')));
    window.addEventListener('sddp:graph-connect', event => openConnectDialog(event.detail || {}));
    window.addEventListener('sddp:graph-edge-click', event => selectEdge(event.detail?.id || null));
    window.addEventListener('sddp:graph-positions', event => savePositions(event.detail || []));
    bindDialogEvents();
  }

  async function savePositions(updates) {
    if (!updates.length || !requireProject()) return;
    try {
      await Promise.all(updates.map(update => {
        const service = services.find(item => item.id === update.id);
        if (!service) return Promise.resolve();
        return api(`${projectBase()}/services/${encodeURIComponent(update.id)}`, {
          method: 'PUT',
          body: {
            name: service.name,
            serviceType: service.serviceType,
            description: service.description,
            positionX: update.positionX,
            positionY: update.positionY,
          },
        }).then(saved => {
          service.positionX = saved.positionX;
          service.positionY = saved.positionY;
        });
      }));
    } catch (error) {
      // Positions are a UI convenience: a failed save must not break the editor.
      console.warn('Failed to save node positions:', error.message);
    }
  }

  window.SDDP.onContextChange(() => {
    loadTopology();
  });

  window.SDDP.onReady(() => {
    bindEvents();
    renderLegend();
    if (showFlash()) { /* keep flash visible */ }
    loadTopology();
  });
})();
