(() => {
  'use strict';
  const { state, esc, api, projectBase, projectUrl, status, requireProject, flash, showFlash, environment,
    refreshEnvironments } = window.SDDP;
  const { t } = window.I18N;

  const byId = id => document.getElementById(id);
  const params = new URLSearchParams(location.search);
  const environmentId = params.get('environmentId') || '';
  const requestedMode = params.get('mode') || '';
  const mode = ['create', 'edit', 'delete'].includes(requestedMode)
    ? requestedMode
    : (environmentId ? 'edit' : 'create');
  const initialProjectId = state.projectId;

  let project = null;
  let environments = [];
  let item = null;

  function projectEnvironmentsUrl() {
    const url = new URL('/project.html', location.origin);
    url.searchParams.set('projectId', state.projectId);
    url.searchParams.set('tab', 'environments');
    return `${url.pathname}?${url.searchParams.toString()}`;
  }

  function editUrl() {
    return window.SDDP.environmentFormUrl(environmentId, 'edit', state.projectId);
  }

  function environmentOptionLabel(env) {
    return `${env.code}${env.name ? ` — ${env.name}` : ''}${env.isProtected ? ` (${t('common.protected')})` : ''}`;
  }

  function renderBreadcrumbs() {
    byId('breadcrumbProject').textContent = project?.name || t('service.breadcrumbProject');
    byId('breadcrumbProject').href = projectUrl();
    byId('breadcrumbEnvironment').textContent = item ? item.code : t('environment.breadcrumb');
  }

  function renderActions() {
    const toProject = `<a class="button-link secondary" href="${projectEnvironmentsUrl()}">${t('environment.toEnvironments')}</a>`;
    byId('formActions').innerHTML = mode === 'delete'
      ? `<a class="button-link secondary" href="${editUrl()}">${t('common.edit')}</a>${toProject}`
      : toProject;
  }

  function renderChrome() {
    renderBreadcrumbs();
    renderActions();
    const titles = {
      create: t('project.environmentFormNew'),
      edit: item ? t('project.editEnvironmentTitle', { code: item.code }) : t('environment.mode.edit'),
      delete: item ? t('environment.mode.deleteNamed', { code: item.code }) : t('environment.mode.delete'),
    };
    const subtitles = {
      create: project ? t('environment.subtitle.create', { name: project.name }) : '',
      edit: t('environment.subtitle.edit'),
      delete: t('environment.subtitle.delete'),
    };
    byId('formTitle').textContent = titles[mode] || t('project.environmentFormNew');
    byId('formSubtitle').textContent = subtitles[mode] || '';
    document.title = `${titles[mode] || t('project.environmentFormNew')} · ${project?.name || t('service.breadcrumbProject')} · SDDP`;
  }

  function showPanel() {
    byId('formPanel').hidden = mode === 'delete';
    byId('deletePanel').hidden = mode !== 'delete';
  }

  function renderEditor() {
    byId('editorTitle').textContent = mode === 'create' ? t('project.environmentFormNew') : t('project.editEnvironmentTitle', { code: item.code });
    byId('formBadge').textContent = mode;
    byId('saveEnvironment').textContent = mode === 'create' ? t('project.createEnvironment') : t('project.saveEnvironment');
    byId('cancelEnvironment').href = projectEnvironmentsUrl();
    byId('environmentCode').value = mode === 'create' ? '' : item.code;
    byId('environmentCode').disabled = false;
    byId('environmentName').value = mode === 'create' ? '' : (item.name || '');
    if (mode === 'create') {
      const max = environments.reduce((acc, env) => Math.max(acc, Number(env.position) || 0), 0);
      byId('environmentPosition').value = String(max + 10);
      byId('environmentProtected').checked = false;
    } else {
      byId('environmentPosition').value = String(item.position ?? 0);
      byId('environmentProtected').checked = Boolean(item.isProtected);
    }
    const protectedEdit = byId('protectedEditRow');
    protectedEdit.hidden = mode !== 'edit' || !item?.isProtected;
    byId('confirmProtectedEdit').checked = false;
    if (!protectedEdit.hidden) {
      byId('confirmProtectedEditText').textContent = t('environment.confirmProtectedRename', { code: item.code });
    }
    byId('environmentName').focus();
  }

  function renderDelete() {
    const current = environment(item.code) || item;
    byId('deleteSummary').innerHTML = `
      <div><span>${t('project.environmentCode')}</span><strong>${esc(item.code)}</strong></div>
      <div><span>${t('project.environmentName')}</span><strong>${esc(item.name || t('common.untitled'))}</strong></div>
      <div><span>${t('project.environmentPosition')}</span><strong>${esc(String(item.position ?? 0))}</strong></div>
      <div><span>${t('environment.summaryConnections')}</span><strong>${esc(String(current.connectionCount ?? 0))}</strong></div>
      <div><span>${t('common.id')}</span><code>${esc(item.id)}</code></div>`;
    byId('cancelDelete').href = projectEnvironmentsUrl();
    byId('confirmDelete').checked = false;
    const protectedRow = byId('protectedDeleteRow');
    if (item.isProtected) {
      protectedRow.hidden = false;
      byId('confirmProtectedDeleteText').textContent = t('environment.confirmProtected', { code: item.code });
    } else {
      protectedRow.hidden = true;
      byId('confirmProtectedDelete').checked = false;
    }
  }

  function defaultStatus() {
    if (mode === 'create') return project ? t('environment.defaultStatus.create', { name: project.name }) : t('project.environmentFormNew');
    if (mode === 'edit') return t('environment.defaultStatus.edit', { code: item.code });
    return t('environment.defaultStatus.delete', { code: item.code });
  }

  function renderFatal(message) {
    byId('formTitle').textContent = t('environment.fatalTitle');
    byId('formSubtitle').textContent = message;
    byId('formActions').innerHTML = `<a class="button-link secondary" href="${projectUrl()}">${t('environment.toProject')}</a>`;
    byId('formPanel').hidden = true;
    byId('deletePanel').hidden = true;
  }

  async function load() {
    if (!requireProject()) {
      renderFatal(t('environment.fatalHint'));
      return;
    }
    try {
      status(t('environment.loading'));
      project = await api(projectBase());
      await refreshEnvironments();
      environments = state.environments;
      if (environmentId) {
        item = environments.find(env => env.id === environmentId) || null;
      }
      if (mode !== 'create' && !item) throw new Error(t('environment.notFound'));
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

  function bindEvents() {
    byId('environmentForm').addEventListener('submit', async event => {
      event.preventDefault();
      if (!project) return;
      const code = byId('environmentCode').value.trim().toUpperCase();
      const name = byId('environmentName').value.trim();
      const position = Number(byId('environmentPosition').value);
      const isProtected = byId('environmentProtected').checked;
      try {
        if (mode === 'edit') {
          const renamingProtected = item?.isProtected && code !== item.code;
          if (renamingProtected && !byId('confirmProtectedEdit').checked) {
            status(t('environment.confirmRequired'), 'warning');
            return;
          }
          await api(`${projectBase()}/environments/${encodeURIComponent(environmentId)}`, {
            method: 'PUT',
            body: { code, name, position, isProtected },
            headers: renamingProtected ? { 'X-SDDP-Confirm-Protected': 'true' } : {},
          });
          flash(t('project.environmentSaved', { code }));
        } else {
          await api(`${projectBase()}/environments`, {
            method: 'POST',
            body: { code, name, position, isProtected },
          });
          flash(t('project.environmentCreated', { code }));
        }
        location.href = projectEnvironmentsUrl();
      } catch (error) { status(error.message, 'error'); }
    });

    byId('deleteForm').addEventListener('submit', async event => {
      event.preventDefault();
      if (!byId('confirmDelete').checked) {
        status(t('environment.confirmRequired'), 'warning');
        return;
      }
      if (item.isProtected && !byId('confirmProtectedDelete').checked) {
        status(t('environment.confirmRequired'), 'warning');
        return;
      }
      try {
        await api(`${projectBase()}/environments/${encodeURIComponent(environmentId)}`, {
          method: 'DELETE',
          headers: item.isProtected ? { 'X-SDDP-Confirm-Protected': 'true' } : {},
        });
        flash(t('project.environmentDeleted', { code: item.code }));
        location.href = projectEnvironmentsUrl();
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
