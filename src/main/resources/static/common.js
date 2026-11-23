(() => {
  'use strict';

  const { t } = window.I18N;

  const NAV = [
    { page: 'projects', href: '/index.html', label: 'nav.projects' },
  ];

  const params = new URLSearchParams(location.search);
  const state = {
    projectId: params.get('projectId') || read('sddp.projectId') || '',
    environment: normalizeEnvironment(params.get('environment') || read('sddp.environment')),
    projects: [],
    environments: [],
  };

  const listeners = new Set();
  let ready;

  function read(key) {
    try { return localStorage.getItem(key) || ''; } catch { return ''; }
  }

  function write(key, value) {
    try {
      if (value) localStorage.setItem(key, value);
      else localStorage.removeItem(key);
    } catch { /* storage may be unavailable in hardened browsers */ }
  }

  function normalizeEnvironment(value) {
    return value && String(value).trim() ? String(value).trim() : 'DEV';
  }

  function environment(code = state.environment) {
    return state.environments.find(item => item.code === code) || null;
  }

  function isProtectedEnvironment(code = state.environment) {
    return Boolean(environment(code)?.isProtected);
  }

  function esc(value) {
    return String(value ?? '').replace(/[&<>"']/g, character => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
    })[character]);
  }

  function fmtDate(value) {
    if (!value) return '—';
    try {
      return new Intl.DateTimeFormat(window.I18N.locale(), { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));
    } catch {
      return String(value);
    }
  }

  function status(message, kind = 'info') {
    const output = document.getElementById('status');
    if (!output) return;
    output.textContent = message || '';
    output.dataset.kind = kind;
  }

  function problemMessage(text) {
    if (!text) return t('common.requestFailed');
    try {
      const problem = JSON.parse(text);
      return problem.detail || problem.message || text;
    } catch {
      return text;
    }
  }

  async function request(path, { method = 'GET', body, headers = {} } = {}) {
    const init = { method, headers: { ...headers } };
    if (body !== undefined) {
      init.headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(body);
    }
    const response = await fetch(path, init);
    if (!response.ok) throw new Error(problemMessage(await response.text()));
    return response;
  }

  async function api(path, options) {
    const response = await request(path, options);
    if (response.status === 204) return null;
    const contentType = response.headers.get('content-type') || '';
    return contentType.includes('json') ? response.json() : response.text();
  }

  function projectBase() {
    return `/api/v1/projects/${encodeURIComponent(state.projectId)}`;
  }

  function projectUrl(projectId = state.projectId) {
    return `/project.html?projectId=${encodeURIComponent(projectId)}`;
  }

  function serviceUrl(serviceId, projectId = state.projectId) {
    return `/service.html?projectId=${encodeURIComponent(projectId)}&serviceId=${encodeURIComponent(serviceId)}`;
  }

  function apiUrl(serviceId, apiId, mode, projectId = state.projectId) {
    const params = new URLSearchParams({ projectId, serviceId });
    if (apiId) params.set('apiId', apiId);
    if (mode) params.set('mode', mode);
    return `/api.html?${params.toString()}`;
  }

  function serviceFormUrl(serviceId, mode, projectId = state.projectId) {
    const params = new URLSearchParams({ projectId });
    if (serviceId) params.set('serviceId', serviceId);
    if (mode) params.set('mode', mode);
    return `/service-form.html?${params.toString()}`;
  }

  function connectionUrl(connectionId, mode, projectId = state.projectId) {
    const params = new URLSearchParams({ projectId });
    if (connectionId) params.set('connectionId', connectionId);
    if (mode) params.set('mode', mode);
    return `/connection.html?${params.toString()}`;
  }

  function environmentFormUrl(environmentId, mode, projectId = state.projectId) {
    const params = new URLSearchParams({ projectId });
    if (environmentId) params.set('environmentId', environmentId);
    if (mode) params.set('mode', mode);
    return `/environment.html?${params.toString()}`;
  }

  function flash(message, kind = 'info') {
    try {
      sessionStorage.setItem('sddp.flash', JSON.stringify({ message, kind }));
    } catch { /* storage may be unavailable in hardened browsers */ }
  }

  function showFlash() {
    let value = null;
    try {
      const raw = sessionStorage.getItem('sddp.flash');
      if (raw) {
        sessionStorage.removeItem('sddp.flash');
        value = JSON.parse(raw);
      }
    } catch { /* storage may be unavailable in hardened browsers */ }
    if (!value?.message) return false;
    status(value.message, value.kind || 'info');
    return true;
  }

  function requireProject() {
    if (state.projectId) return true;
    status(t('common.selectProjectFirst'), 'warning');
    return false;
  }

  function parseJson(text, label) {
    try {
      return JSON.parse(text);
    } catch (error) {
      throw new Error(t('common.invalidJson', { label, message: error.message }));
    }
  }

  function nextVersion(version) {
    const parts = String(version || '1').split('.');
    for (let index = parts.length - 1; index >= 0; index -= 1) {
      if (/^\d+$/.test(parts[index])) {
        parts[index] = String(Number(parts[index]) + 1);
        return parts.join('.');
      }
    }
    return `${version}-next`;
  }

  function compareVersions(left, right) {
    return String(left ?? '').localeCompare(String(right ?? ''), undefined, { numeric: true, sensitivity: 'base' });
  }

  function latestApis(items) {
    const latest = new Map();
    for (const item of items || []) {
      const key = `${item.serviceId || ''}:${item.name || ''}`;
      const current = latest.get(key);
      if (!current || compareVersions(item.version, current.version) > 0) latest.set(key, item);
    }
    return [...latest.values()];
  }

  function saveBlob(blob, filename) {
    const link = document.createElement('a');
    link.href = URL.createObjectURL(blob);
    link.download = filename;
    link.click();
    setTimeout(() => URL.revokeObjectURL(link.href), 0);
  }

  async function download(path, filename, options) {
    const response = await request(path, options);
    saveBlob(await response.blob(), filename);
  }

  function downloadText(text, filename, type = 'application/json') {
    saveBlob(new Blob([text], { type }), filename);
  }

  function environmentOptions() {
    const values = state.environments.length
      ? state.environments
      : [{ code: state.environment || 'DEV', name: '', isProtected: false }];
    return values.map(item => {
      const label = `${item.code}${item.name ? ` — ${item.name}` : ''}${item.isProtected ? ` (${t('common.protected')})` : ''}`;
      return `<option value="${esc(item.code)}"${item.code === state.environment ? ' selected' : ''}>${esc(label)}</option>`;
    }).join('');
  }

  function renderEnvironmentOptions() {
    const select = document.getElementById('environmentSelect');
    if (!select) return;
    select.innerHTML = environmentOptions();
    select.value = state.environment;
  }

  async function refreshEnvironments() {
    if (!state.projectId) {
      state.environments = [];
      renderEnvironmentOptions();
      return state.environments;
    }
    try {
      state.environments = await api(`${projectBase()}/environments`);
    } catch {
      state.environments = [];
    }
    if (!state.environments.some(item => item.code === state.environment)) {
      state.environment = state.environments[0]?.code || 'DEV';
      write('sddp.environment', state.environment);
    }
    renderEnvironmentOptions();
    syncUrl();
    return state.environments;
  }

  function languageOptions() {
    const names = { en: 'English', ru: 'Русский' };
    return window.I18N.supported.map(code => (
      `<option value="${code}"${code === window.I18N.language ? ' selected' : ''}>${names[code] || code}</option>`
    )).join('');
  }

  function renderShell() {
    const shell = document.getElementById('app-shell');
    if (!shell) return;
    const page = document.body.dataset.page;
    shell.innerHTML = `
      <header class="topbar">
        <div class="brand">
          <span class="brand-mark">SD</span>
          <div><strong>SDDP</strong><small>${t('shell.subtitle')}</small></div>
        </div>
        <nav class="main-nav" aria-label="${t('shell.mainNav')}">
          ${NAV.map(item => `<a href="${item.href}"${item.page === page ? ' aria-current="page"' : ''}>${t(item.label)}</a>`).join('')}
        </nav>
        <label class="context-field language-field"><span>${t('shell.language')}</span>
          <select id="languageSelect" aria-label="${t('shell.language')}">${languageOptions()}</select>
        </label>
      </header>
      <div class="context-bar">
        ${page !== 'projects'
          ? `<label class="context-field"><span>${t('shell.environment')}</span><select id="environmentSelect" aria-label="${t('shell.environment')}">
              ${environmentOptions()}
            </select></label>`
          : `<span class="context-note">${t('shell.projectsHint')}</span>`}
        <output id="status" class="status" role="status" aria-live="polite"></output>
      </div>`;
    document.getElementById('languageSelect')?.addEventListener('change', event => window.I18N.setLanguage(event.target.value));
  }

  function bindControls() {
    document.getElementById('environmentSelect')?.addEventListener('change', event => setEnvironment(event.target.value));
  }

  function notify() {
    const detail = { projectId: state.projectId, environment: state.environment };
    document.dispatchEvent(new CustomEvent('sddp:context-change', { detail }));
    listeners.forEach(listener => listener(detail));
  }

  function syncUrl() {
    const url = new URL(location.href);
    if (state.projectId) url.searchParams.set('projectId', state.projectId);
    else url.searchParams.delete('projectId');
    if (state.environment !== 'DEV') url.searchParams.set('environment', state.environment);
    else url.searchParams.delete('environment');
    history.replaceState(null, '', url);
  }

  function setProject(projectId, options = {}) {
    state.projectId = projectId || '';
    write('sddp.projectId', state.projectId);
    syncUrl();
    if (options.notify !== false) notify();
  }

  function setEnvironment(value) {
    const requested = String(value || '').trim();
    state.environment = state.environments.some(item => item.code === requested)
      ? requested
      : (state.environments[0]?.code || normalizeEnvironment(requested));
    write('sddp.environment', state.environment);
    const select = document.getElementById('environmentSelect');
    if (select) select.value = state.environment;
    syncUrl();
    notify();
  }

  async function refreshProjects() {
    state.projects = await api('/api/v1/projects');
    if (!state.projects.some(project => project.id === state.projectId)) {
      setProject(state.projects[0]?.id || '', { notify: false });
    }
    return state.projects;
  }

  function onReady(callback) {
    Promise.resolve(ready).then(callback).catch(error => status(error.message, 'error'));
  }

  function onContextChange(callback) {
    listeners.add(callback);
  }

  async function init() {
    renderShell();
    bindControls();
    try {
      await refreshProjects();
      await refreshEnvironments();
    } catch (error) {
      status(error.message, 'error');
    }
    syncUrl();
  }

  window.SDDP = {
    state,
    api,
    projectBase,
    projectUrl,
    serviceUrl,
    apiUrl,
    serviceFormUrl,
    connectionUrl,
    environmentFormUrl,
    flash,
    showFlash,
    requireProject,
    parseJson,
    nextVersion,
    compareVersions,
    latestApis,
    status,
    esc,
    fmtDate,
    download,
    downloadText,
    environment,
    isProtectedEnvironment,
    environmentOptions,
    refreshEnvironments,
    refreshProjects,
    setProject,
    setEnvironment,
    onReady,
    onContextChange,
  };

  ready = init();
})();
