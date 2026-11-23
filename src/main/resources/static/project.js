(() => {
  'use strict';
  const { state, esc, api, projectBase, serviceUrl, status, requireProject, fmtDate, latestApis,
    serviceFormUrl, connectionUrl, environmentFormUrl, environment, isProtectedEnvironment,
    refreshEnvironments } = window.SDDP;
  const { t } = window.I18N;

  const byId = id => document.getElementById(id);

  let project = null;
  let services = [];
  let apis = [];
  let environments = [];
  let topology = { nodes: [], edges: [] };
  let plugins = [];
  let irManifest = null;
  let selectedTab = 'overview';

  function serviceName(id) {
    return services.find(service => service.id === id)?.name || id;
  }

  function apiLabel(item) {
    return item ? `${item.name} v${item.version}` : '';
  }

  function typeBadge(type) {
    const kind = String(type || 'CORE').toLowerCase();
    return `<span class="badge ${kind}">${esc(type || 'CORE')}</span>`;
  }

  function switchTab(tab) {
    selectedTab = tab;
    document.querySelectorAll('[data-tab]').forEach(button => {
      const active = button.dataset.tab === tab;
      button.setAttribute('aria-current', active ? 'page' : 'false');
    });
    document.querySelectorAll('[data-panel]').forEach(panel => {
      panel.hidden = panel.dataset.panel !== tab;
    });
    const url = new URL(location.href);
    url.searchParams.set('tab', tab);
    history.replaceState(null, '', url);
  }

  function initTabs() {
    document.querySelectorAll('[data-tab]').forEach(button => {
      button.addEventListener('click', () => switchTab(button.dataset.tab));
    });
    const requested = new URLSearchParams(location.search).get('tab');
    switchTab(['overview', 'services', 'connections', 'environments', 'build'].includes(requested) ? requested : 'overview');
  }

  function environmentCard(item) {
    const active = item.code === state.environment;
    return `<article class="environment-card${active ? ' active' : ''}">
      <header>
        <h3>${esc(item.code)}</h3>
        <div class="button-row">
          ${item.isProtected ? `<span class="badge protected">${t('common.protected')}</span>` : ''}
          ${active ? `<span class="badge ok">${t('common.current')}</span>` : ''}
        </div>
      </header>
      <p class="hint">${esc(item.name || t('common.untitled'))}</p>
      <div class="project-meta">
        <span>${t('common.connectionsCount', { count: item.connectionCount })}</span>
        <span>${t('common.position', { position: item.position })}</span>
      </div>
      <div class="project-actions">
        <button type="button" class="secondary" data-switch-environment="${esc(item.code)}"${active ? ' disabled' : ''}>${t('project.switchEnvironment')}</button>
      </div>
    </article>`;
  }

  function renderOverview() {
    byId('statServices').textContent = String(services.length);
    byId('statConnections').textContent = String(topology.edges.length);
    byId('statApis').textContent = String(latestApis(apis).length);
    byId('statEnvironments').textContent = String(environments.length);
    byId('overviewEnvironmentCount').textContent = String(environments.length);
    byId('overviewEnvironments').innerHTML = environments.length
      ? environments.map(environmentCard).join('')
      : `<div class="empty-state">${t('project.overview.emptyEnvironments')}</div>`;
    byId('projectTitle').textContent = project?.name || t('project.heading');
    const protectedNote = isProtectedEnvironment(state.environment) ? t('project.subtitle.protected') : '';
    byId('projectSubtitle').textContent = project
      ? t('project.subtitle.created', { date: fmtDate(project.createdAt), environment: state.environment }) + protectedNote
      : t('project.subtitle.select');
    byId('breadcrumbProject').textContent = project?.name || t('project.breadcrumb');
    document.title = `${project?.name || t('project.breadcrumb')} · SDDP`;
  }

  function environmentOptionLabel(item) {
    return `${item.code}${item.isProtected ? ` (${t('common.protected')})` : ''}`;
  }

  function fillEnvironmentSelect(select, selected, emptyLabel) {
    if (!select) return;
    const options = [];
    if (emptyLabel !== null) options.push(`<option value="">${esc(emptyLabel)}</option>`);
    options.push(...environments.map(item => `<option value="${esc(item.code)}">${esc(environmentOptionLabel(item))}</option>`));
    select.innerHTML = options.join('') || `<option value="">${t('common.noEnvironments')}</option>`;
    if (selected && environments.some(item => item.code === selected)) select.value = selected;
    else if (emptyLabel === null && environments[0]) select.value = environments[0].code;
    else select.value = '';
  }

  function defaultCompareTarget() {
    return environments.find(item => item.code !== state.environment)?.code || state.environment;
  }

  function renderEnvironmentSelects() {
    fillEnvironmentSelect(byId('compareSource'), byId('compareSource').value || state.environment, null);
    fillEnvironmentSelect(byId('compareTarget'), byId('compareTarget').value || defaultCompareTarget(), null);
    fillEnvironmentSelect(byId('copySource'), byId('copySource').value || state.environment, null);
    fillEnvironmentSelect(byId('copyTarget'), byId('copyTarget').value || defaultCompareTarget(), null);
    fillEnvironmentSelect(byId('snapshotEnvironment'), byId('snapshotEnvironment').value || '', t('project.allEnvironments'));
  }

  function renderEnvironments() {
    byId('environmentCount').textContent = String(environments.length);
    byId('environmentList').innerHTML = environments.length
      ? environments.map(item => {
        const active = item.code === state.environment;
        return `<div class="list-row${active ? ' selected' : ''}">
          <div class="list-main">
            <strong>${esc(item.code)} ${item.isProtected ? `<span class="badge protected">${t('common.protected')}</span>` : ''} ${active ? `<span class="badge ok">${t('common.current')}</span>` : ''}</strong>
            <small>${esc(item.name || t('common.untitled'))} · ${t('common.connectionsCount', { count: item.connectionCount })} · ${t('common.position', { position: item.position })}</small>
          </div>
          <div class="list-actions">
            <button class="secondary" type="button" data-switch-environment="${esc(item.code)}"${active ? ' disabled' : ''}>${t('project.switchEnvironment')}</button>
            <a class="button-link secondary" href="${environmentFormUrl(item.id, 'edit')}">${t('common.edit')}</a>
            <a class="button-link danger" href="${environmentFormUrl(item.id, 'delete')}">${t('common.delete')}</a>
          </div>
        </div>`;
      }).join('')
      : `<div class="empty-state">${t('project.environmentsEmpty')}</div>`;
    renderEnvironmentSelects();
  }

  function renderServices() {
    byId('serviceCount').textContent = String(services.length);
    if (!services.length) {
      byId('serviceList').innerHTML = `<div class="empty-state">${t('project.servicesEmpty')}</div>`;
      return;
    }
    const latest = latestApis(apis);
    byId('serviceList').innerHTML = services.map(service => `
      <article class="service-card">
        <header>
          <h3>${esc(service.name)}</h3>
          ${typeBadge(service.serviceType)}
        </header>
        <p class="hint">${esc(service.description || t('common.noDescription'))}</p>
        <div class="project-meta">
          <span>${t('project.servicesApiCount', { count: latest.filter(item => item.serviceId === service.id).length })}</span>
          <span>${t('common.id')}: <code>${esc(service.id)}</code></span>
        </div>
        <div class="project-actions">
          <a href="${serviceUrl(service.id)}">${t('project.openService')}</a>
          <a class="button-link secondary" href="${serviceFormUrl(service.id, 'edit')}">${t('common.edit')}</a>
          <a class="button-link danger" href="${serviceFormUrl(service.id, 'delete')}">${t('common.delete')}</a>
        </div>
      </article>`).join('');
  }

  function renderConnections() {
    byId('connectionCount').textContent = String(topology.edges.length);
    const current = environment(state.environment);
    byId('connectionEnvironmentNote').textContent = current
      ? t('project.connectionEnvironment', {
          code: current.code,
          protected: current.isProtected ? t('project.connectionEnvironmentProtected') : '',
        })
      : '';
    if (!topology.edges.length) {
      byId('connectionList').innerHTML = `<div class="empty-state">${t('project.connectionsEmpty')}</div>`;
      return;
    }
    byId('connectionList').innerHTML = topology.edges.map(edge => `
      <div class="list-row" data-connection="${esc(edge.id)}">
        <div class="list-main">
          <strong>${esc(serviceName(edge.source))} <span class="arrow">→</span> ${esc(serviceName(edge.target))}</strong>
          <small>${esc(edge.sourceApi || t('project.noSourceApi'))} → ${esc(edge.targetApi || t('project.noTargetApi'))} · ${esc(edge.transportType)}</small>
          <small>${edge.mappingRules ? `<span class="badge ok">${t('project.mappingConfigured')}</span>` : `<span class="badge warn">${t('project.noMapping')}</span>`}</small>
        </div>
        <div class="list-actions">
          <a class="button-link secondary" href="${connectionUrl(edge.id, 'edit')}">${t('common.edit')}</a>
          <a class="button-link danger" href="${connectionUrl(edge.id, 'delete')}">${t('common.delete')}</a>
        </div>
      </div>`).join('');
  }

  function renderAll() {
    renderOverview();
    renderServices();
    renderConnections();
    renderEnvironments();
  }

  async function loadProjectData() {
    if (!requireProject()) {
      project = null; services = []; apis = []; environments = []; topology = { nodes: [], edges: [] };
      state.environments = [];
      renderAll();
      return;
    }
    try {
      status(t('project.loadingProject'));
      project = await api(projectBase());
      await refreshEnvironments();
      environments = state.environments;
      [services, apis, topology] = await Promise.all([
        api(`${projectBase()}/services`),
        api(`${projectBase()}/apis`),
        api(`${projectBase()}/topology?environment=${encodeURIComponent(state.environment)}`),
      ]);
      renderAll();
      status(t('project.loaded', { name: project.name, services: services.length, connections: topology.edges.length, count: environments.length }));
    } catch (error) {
      status(error.message, 'error');
    }
  }

  function protectedHeaders(code = state.environment) {
    return isProtectedEnvironment(code) ? { 'X-SDDP-Confirm-Protected': 'true' } : {};
  }

  function confirmProtectedWrite(code, action) {
    if (!isProtectedEnvironment(code)) return true;
    return window.confirm(t('project.protectedConfirm', { code, action }));
  }

  function differenceLabel(kind) {
    return {
      MISSING_IN_TARGET: t('project.diffMissingInTarget'),
      MISSING_IN_SOURCE: t('project.diffMissingInSource'),
      CHANGED: t('project.diffChanged'),
    }[kind] || kind;
  }

  function differenceClass(kind) {
    return kind === 'CHANGED' ? 'warn' : 'accent';
  }

  function renderComparison(result) {
    const summary = byId('comparisonSummary');
    summary.classList.remove('empty-state');
    summary.innerHTML = t('project.comparisonSummary', {
      a: `<strong>${esc(result.sourceEnvironment)}</strong>`,
      ac: result.sourceConnections,
      b: `<strong>${esc(result.targetEnvironment)}</strong>`,
      bc: result.targetConnections,
      count: `<strong>${result.differences.length}</strong>`,
    });
    if (!result.differences.length) {
      byId('comparisonList').innerHTML = `<div class="empty-state">${t('project.comparisonEqual')}</div>`;
      return;
    }
    byId('comparisonList').innerHTML = result.differences.map(difference => `
      <div class="diff-row">
        <span class="badge ${differenceClass(difference.kind)}">${esc(differenceLabel(difference.kind))}</span>
        <div class="diff-main">
          ${difference.sourceLabel ? `<small>A: ${esc(difference.sourceLabel)}</small>` : ''}
          ${difference.targetLabel ? `<small>B: ${esc(difference.targetLabel)}</small>` : ''}
          ${difference.changedFields?.length ? `<small>${t('project.changedFields', { fields: difference.changedFields.map(esc).join(', ') })}</small>` : ''}
        </div>
      </div>`).join('');
  }

  function resetComparison() {
    byId('comparisonSummary').classList.add('empty-state');
    byId('comparisonSummary').textContent = t('project.comparePlaceholder');
    byId('comparisonList').innerHTML = '';
  }

  function renderFiles(files) {
    const entries = Object.entries(files || {});
    if (!entries.length) {
      byId('codegenResult').innerHTML = `<div class="empty-state">${t('project.noFiles')}</div>`;
      return;
    }
    byId('resultBadge').textContent = t('project.filesCount', { count: entries.length, environment: state.environment });
    byId('codegenResult').innerHTML = entries.map(([name, content]) => `
      <article class="generated-file">
        <header>
          <strong>${esc(name)}</strong>
          <button class="secondary" type="button" data-file="${esc(name)}">${t('project.download')}</button>
        </header>
        <pre>${esc(content)}</pre>
      </article>`).join('');
  }

  function pluginOptionLabel(plugin) {
    return `${plugin.language} · ${plugin.deliveryType} · ${plugin.executableTarget}`;
  }

  async function loadPlugins() {
    try {
      plugins = await api('/api/v1/codegen/plugins');
      byId('pluginSelect').innerHTML = [`<option value="">${t('project.builtinGenerator')}</option>`]
        .concat(plugins.map((plugin, index) => `<option value="${index}">${esc(pluginOptionLabel(plugin))}</option>`))
        .join('');
    } catch (error) {
      status(error.message, 'error');
    }
  }

  async function buildIr() {
    if (!requireProject()) return;
    try {
      status(t('project.buildingIr'));
      irManifest = await api(`${projectBase()}/ir?environment=${encodeURIComponent(state.environment)}`, { method: 'POST' });
      const connections = (irManifest.services || []).reduce((total, service) => total + (service.outbound_connections?.length || 0), 0);
      byId('irOutput').textContent = JSON.stringify(irManifest, null, 2);
      byId('irOutput').hidden = true;
      byId('toggleIrJson').hidden = false;
      byId('toggleIrJson').textContent = t('common.showJson');
      byId('irEmpty').hidden = false;
      byId('irEmpty').classList.add('summary');
      byId('irEmpty').textContent = t('project.irSummary', { environment: state.environment, services: irManifest.services?.length || 0, connections });
      byId('downloadIr').disabled = false;
      status(t('project.irBuilt', { environment: state.environment }));
    } catch (error) {
      irManifest = null;
      byId('downloadIr').disabled = true;
      byId('irOutput').hidden = true;
      byId('toggleIrJson').hidden = true;
      byId('irEmpty').hidden = false;
      byId('irEmpty').classList.remove('summary');
      byId('irEmpty').textContent = t('project.irFailed', { message: error.message });
      status(error.message, 'error');
    }
  }

  function resetGeneration() {
    irManifest = null;
    byId('downloadIr').disabled = true;
    byId('irOutput').textContent = '';
    byId('irOutput').hidden = true;
    byId('toggleIrJson').hidden = true;
    byId('toggleIrJson').textContent = t('common.showJson');
    byId('irEmpty').hidden = false;
    byId('irEmpty').classList.remove('summary');
    byId('irEmpty').textContent = t('project.irEmptyReset');
    byId('resultBadge').textContent = t('project.notRun');
    byId('codegenResult').innerHTML = `<div class="empty-state">${t('project.resultEmpty')}</div>`;
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

  function codegenRequest() {
    return {
      projectId: state.projectId,
      environment: state.environment,
      packageName: byId('packageName').value.trim(),
      clientName: byId('clientName').value.trim(),
    };
  }

  function bindEvents() {
    document.querySelectorAll('[data-goto]').forEach(button => {
      button.addEventListener('click', () => switchTab(button.dataset.goto));
    });
    bindJsonToggles();
    byId('reloadProject').addEventListener('click', loadProjectData);
    byId('exportSnapshot').addEventListener('click', async () => {
      if (!project) return;
      const snapshotEnvironment = byId('snapshotEnvironment').value;
      try {
        const query = snapshotEnvironment ? `?environment=${encodeURIComponent(snapshotEnvironment)}` : '';
        const suffix = snapshotEnvironment ? `-${snapshotEnvironment.toLowerCase()}` : '';
        const name = `${project.name.replace(/[^A-Za-z0-9._-]+/g, '-')}-snapshot${suffix}.json`;
        await window.SDDP.download(`${projectBase()}/snapshot${query}`, name, { method: 'POST' });
        status(snapshotEnvironment ? t('project.snapshotEnvironmentDownloaded', { environment: snapshotEnvironment }) : t('project.snapshotAllDownloaded'));
      } catch (error) { status(error.message, 'error'); }
    });

    byId('environmentList').addEventListener('click', event => {
      const switchButton = event.target.closest('[data-switch-environment]');
      if (switchButton) window.SDDP.setEnvironment(switchButton.dataset.switchEnvironment);
    });
    byId('overviewEnvironments').addEventListener('click', event => {
      const switchButton = event.target.closest('[data-switch-environment]');
      if (switchButton) window.SDDP.setEnvironment(switchButton.dataset.switchEnvironment);
    });

    byId('compareEnvironments').addEventListener('click', async () => {
      if (!requireProject()) return;
      const source = byId('compareSource').value;
      const target = byId('compareTarget').value;
      if (!source || !target) { status(t('project.selectTwoEnvironments'), 'warning'); return; }
      try {
        status(t('project.comparing'));
        const result = await api(`${projectBase()}/environments/compare?sourceEnvironment=${encodeURIComponent(source)}&targetEnvironment=${encodeURIComponent(target)}`);
        renderComparison(result);
        status(t('project.comparisonStatus', { a: source, b: target, count: result.differences.length }));
      } catch (error) { status(error.message, 'error'); }
    });

    byId('copyConnections').addEventListener('click', async () => {
      if (!requireProject()) return;
      const sourceEnvironment = byId('copySource').value;
      const targetEnvironment = byId('copyTarget').value;
      const mode = byId('copyMode').value;
      if (!sourceEnvironment || !targetEnvironment) { status(t('project.selectSourceTarget'), 'warning'); return; }
      if (sourceEnvironment === targetEnvironment) { status(t('project.sameSourceTarget'), 'warning'); return; }
      const action = mode === 'REPLACE'
        ? t('project.copyActionReplace', { target: targetEnvironment })
        : t('project.copyActionMerge', { target: targetEnvironment });
      if (!window.confirm(t('project.confirmCopy', { source: sourceEnvironment, action }))) return;
      if (!confirmProtectedWrite(targetEnvironment, t('project.copyConfirmAction'))) return;
      try {
        status(t('project.copying'));
        const result = await api(`${projectBase()}/connections/copy`, {
          method: 'POST',
          body: { sourceEnvironment, targetEnvironment, mode },
          headers: protectedHeaders(targetEnvironment),
        });
        status(t('project.copyResult', { copied: result.copied, skipped: result.skipped, removed: result.removed }));
        resetComparison();
        await loadProjectData();
      } catch (error) { status(error.message, 'error'); }
    });

    byId('buildIr').addEventListener('click', buildIr);
    byId('downloadIr').addEventListener('click', () => {
      if (!irManifest) return;
      window.SDDP.downloadText(JSON.stringify(irManifest, null, 2), `${project?.name || 'project'}-ir-${state.environment}.json`);
    });
    byId('reloadPlugins').addEventListener('click', loadPlugins);
    byId('pluginSelect').addEventListener('change', () => {
      const plugin = byId('pluginSelect').value === '' ? null : plugins[Number(byId('pluginSelect').value)];
      byId('pluginHint').textContent = plugin
        ? t('project.pluginMustBeRegistered', { plugin: pluginOptionLabel(plugin) })
        : t('project.pluginHint');
    });
    byId('codegenForm').addEventListener('submit', async event => {
      event.preventDefault();
      if (!requireProject()) return;
      try {
        status(t('project.generating'));
        const body = codegenRequest();
        const plugin = byId('pluginSelect').value === '' ? null : plugins[Number(byId('pluginSelect').value)];
        if (plugin) body.plugin = { language: plugin.language, deliveryType: plugin.deliveryType, executableTarget: plugin.executableTarget };
        const result = await api('/api/v1/codegen/generate', { method: 'POST', body });
        const files = result.files && Object.keys(result.files).length
          ? result.files
          : (result.fileName ? { [result.fileName]: result.source } : {});
        renderFiles(files);
        status(t('project.generated', { count: Object.keys(files).length }));
      } catch (error) {
        byId('resultBadge').textContent = t('project.error');
        byId('codegenResult').innerHTML = `<div class="empty-state">${esc(error.message)}</div>`;
        status(error.message, 'error');
      }
    });
    byId('artifactForm').addEventListener('submit', async event => {
      event.preventDefault();
      const button = byId('downloadArtifact');
      if (button.disabled || !requireProject() || !byId('codegenForm').reportValidity()) return;
      const body = { ...codegenRequest(), version: byId('artifactVersion').value };
      const filename = `${body.clientName}-${body.version}.zip`;
      button.disabled = true;
      try {
        status(t('project.generating'));
        await window.SDDP.download('/api/v1/codegen/artifact', filename, { method: 'POST', body });
        status(t('project.artifactDownloaded', { filename }));
      } catch (error) {
        status(error.message, 'error');
      } finally {
        button.disabled = false;
      }
    });
    byId('codegenResult').addEventListener('click', event => {
      const button = event.target.closest('[data-file]');
      if (!button) return;
      const content = button.closest('.generated-file')?.querySelector('pre')?.textContent || '';
      window.SDDP.downloadText(content, button.dataset.file, 'text/plain');
    });
  }

  window.SDDP.onContextChange(() => {
    resetGeneration();
    resetComparison();
    loadProjectData();
  });

  window.SDDP.onReady(() => {
    initTabs();
    byId('newService').href = serviceFormUrl(null, 'create');
    byId('newConnection').href = connectionUrl(null, 'create');
    byId('newEnvironment').href = environmentFormUrl(null, 'create');
    byId('graphEditor').href = `/graph.html?projectId=${encodeURIComponent(state.projectId)}`;
    bindEvents();
    resetComparison();
    loadPlugins();
    loadProjectData();
  });
})();
