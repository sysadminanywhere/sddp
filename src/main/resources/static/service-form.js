(() => {
  'use strict';
  const { state, esc, api, projectBase, projectUrl, serviceUrl, status, requireProject, fmtDate, flash, showFlash } = window.SDDP;
  const { t } = window.I18N;

  const byId = id => document.getElementById(id);
  const params = new URLSearchParams(location.search);
  const serviceId = params.get('serviceId') || '';
  const requestedMode = params.get('mode') || '';
  const mode = ['create', 'edit', 'delete'].includes(requestedMode)
    ? requestedMode
    : (serviceId ? 'edit' : 'create');
  const initialProjectId = state.projectId;

  let project = null;
  let service = null;

  function viewUrl() {
    return serviceUrl(serviceId, state.projectId);
  }

  function editUrl() {
    return window.SDDP.serviceFormUrl(serviceId, 'edit', state.projectId);
  }

  function deleteUrl() {
    return window.SDDP.serviceFormUrl(serviceId, 'delete', state.projectId);
  }

  function serviceLabel(value) {
    return value?.name || '';
  }

  function renderBreadcrumbs() {
    byId('breadcrumbProject').textContent = project?.name || t('service.breadcrumbProject');
    byId('breadcrumbProject').href = projectUrl();
    byId('breadcrumbService').textContent = service?.name || t('service.breadcrumbService');
  }

  function renderActions() {
    const toProject = `<a class="button-link secondary" href="${projectUrl()}">${t('serviceForm.toProject')}</a>`;
    if (mode === 'edit' || mode === 'delete') {
      byId('formActions').innerHTML = `
        ${mode === 'edit' ? `<a class="button-link secondary" href="${viewUrl()}">${t('serviceForm.toService')}</a>` : `<a class="button-link secondary" href="${editUrl()}">${t('common.edit')}</a>`}
        ${toProject}`;
      return;
    }
    byId('formActions').innerHTML = toProject;
  }

  function renderChrome() {
    renderBreadcrumbs();
    renderActions();
    const titles = {
      create: t('serviceForm.mode.create'),
      edit: service ? t('serviceForm.mode.editNamed', { name: service.name }) : t('serviceForm.mode.edit'),
      delete: service ? t('serviceForm.mode.deleteNamed', { name: service.name }) : t('serviceForm.mode.delete'),
    };
    const subtitles = {
      create: project ? t('serviceForm.subtitle.create', { name: project.name }) : t('serviceForm.subtitle.createNoProject'),
      edit: t('serviceForm.subtitle.edit'),
      delete: t('serviceForm.subtitle.delete'),
    };
    byId('formTitle').textContent = titles[mode] || t('serviceForm.mode.create');
    byId('formSubtitle').textContent = subtitles[mode] || '';
    document.title = `${titles[mode] || t('serviceForm.mode.create')} · ${project?.name || t('service.breadcrumbProject')} · SDDP`;
  }

  function showPanel() {
    byId('formPanel').hidden = mode === 'delete';
    byId('deletePanel').hidden = mode !== 'delete';
  }

  function renderEditor() {
    byId('editorTitle').textContent = mode === 'create' ? t('serviceForm.mode.create') : t('serviceForm.mode.edit');
    byId('formBadge').textContent = mode;
    byId('saveService').textContent = mode === 'create' ? t('serviceForm.save.create') : t('serviceForm.save.edit');
    byId('cancelService').href = mode === 'create' ? projectUrl() : viewUrl();

    if (mode === 'create') {
      byId('serviceName').value = '';
      byId('serviceType').value = 'CORE';
      byId('serviceDescription').value = '';
      byId('serviceName').focus();
    } else {
      byId('serviceName').value = service.name;
      byId('serviceType').value = service.serviceType;
      byId('serviceDescription').value = service.description || '';
      byId('serviceName').focus();
    }
  }

  function renderDelete() {
    byId('deleteSummary').innerHTML = `
      <div><span>${t('common.name')}</span><strong>${esc(service.name)}</strong></div>
      <div><span>${t('service.type')}</span><strong>${esc(service.serviceType)}</strong></div>
      <div><span>${t('common.created')}</span><strong>${esc(fmtDate(service.createdAt))}</strong></div>
      <div><span>${t('common.id')}</span><code>${esc(service.id)}</code></div>`;
    byId('cancelDelete').href = viewUrl();
    byId('confirmDelete').checked = false;
  }

  function defaultStatus() {
    if (mode === 'create') return project ? t('serviceForm.defaultStatus.create', { name: project.name }) : t('serviceForm.defaultStatus.createNoProject');
    if (mode === 'edit') return t('serviceForm.defaultStatus.edit', { name: service.name });
    return t('serviceForm.defaultStatus.delete', { name: service.name });
  }

  function renderFatal(message) {
    byId('formTitle').textContent = t('serviceForm.fatalTitle');
    byId('formSubtitle').textContent = message;
    byId('formActions').innerHTML = `<a class="button-link secondary" href="${projectUrl()}">${t('serviceForm.toProject')}</a>`;
    byId('formPanel').hidden = true;
    byId('deletePanel').hidden = true;
  }

  async function load() {
    if (!requireProject()) {
      renderFatal(t('serviceForm.fatalHint'));
      return;
    }
    try {
      status(t('serviceForm.loading'));
      project = await api(projectBase());
      if (serviceId) {
        service = await api(`${projectBase()}/services/${encodeURIComponent(serviceId)}`);
      }
      if (mode !== 'create' && !service) throw new Error(t('serviceForm.notFound'));
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
    byId('serviceForm').addEventListener('submit', async event => {
      event.preventDefault();
      if (!project) return;
      try {
        const body = {
          name: byId('serviceName').value.trim(),
          serviceType: byId('serviceType').value,
          description: byId('serviceDescription').value.trim() || null,
        };
        let saved;
        if (mode === 'edit') {
          saved = await api(`${projectBase()}/services/${encodeURIComponent(serviceId)}`, { method: 'PUT', body });
          flash(t('serviceForm.flashSaved', { name: saved.name }));
        } else {
          saved = await api(`${projectBase()}/services`, { method: 'POST', body });
          flash(t('serviceForm.flashCreated', { name: saved.name }));
        }
        location.href = serviceUrl(saved.id, state.projectId);
      } catch (error) { status(error.message, 'error'); }
    });

    byId('deleteForm').addEventListener('submit', async event => {
      event.preventDefault();
      if (!byId('confirmDelete').checked) {
        status(t('serviceForm.confirmRequired'), 'warning');
        return;
      }
      try {
        await api(`${projectBase()}/services/${encodeURIComponent(serviceId)}`, { method: 'DELETE' });
        flash(t('serviceForm.flashDeleted', { name: service.name }));
        location.href = projectUrl();
      } catch (error) {
        const message = error.message.includes('used by')
          ? t('serviceForm.usedByConnections')
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
    bindEvents();
    load();
  });
})();
