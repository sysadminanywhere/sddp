(() => {
  'use strict';
  const { state, esc, fmtDate, status } = window.SDDP;
  const { t } = window.I18N;

  const list = document.getElementById('projectList');
  const count = document.getElementById('projectCount');

  function openProject(projectId) {
    window.SDDP.setProject(projectId, { notify: false });
    location.href = window.SDDP.projectUrl(projectId);
  }

  function render() {
    count.textContent = String(state.projects.length);
    if (!state.projects.length) {
      list.innerHTML = `<div class="empty-state">${t('index.empty')}</div>`;
      return;
    }
    list.innerHTML = state.projects.map(project => {
      const active = project.id === state.projectId;
      return `
        <article class="project-card${active ? ' active' : ''}">
          <div>
            <h3>${esc(project.name)}</h3>
            <div class="project-meta">
              <span>${active ? `<span class="badge ok">${t('index.activeProject')}</span>` : `<span class="badge">${t('index.project')}</span>`}</span>
              <span>${t('common.id')}: <code>${esc(project.id)}</code></span>
              <span>${t('index.created')}: ${esc(fmtDate(project.createdAt))}</span>
            </div>
          </div>
          <div class="project-actions">
            <button type="button" data-open="${esc(project.id)}">${t('index.openProject')}</button>
            <button type="button" data-snapshot="${esc(project.id)}" data-name="${esc(project.name)}">${esc(t('index.snapshot'))}</button>
            <button type="button" class="danger" data-delete="${esc(project.id)}" data-name="${esc(project.name)}">${esc(t('index.deleteProject'))}</button>
          </div>
        </article>`;
    }).join('');
  }

  list.addEventListener('click', async event => {
    const open = event.target.closest('[data-open]');
    if (open) {
      openProject(open.dataset.open);
      return;
    }
    const remove = event.target.closest('[data-delete]');
    if (remove) {
      const name = remove.dataset.name;
      if (!window.confirm(t('index.confirmDelete', { name }))) return;
      try {
        await window.SDDP.api(`/api/v1/projects/${encodeURIComponent(remove.dataset.delete)}`, { method: 'DELETE' });
        await window.SDDP.refreshProjects();
        render();
        status(t('index.projectDeleted', { name }));
      } catch (error) {
        status(error.message, 'error');
      }
      return;
    }
    const snapshot = event.target.closest('[data-snapshot]');
    if (!snapshot) return;
    try {
      const filename = `${snapshot.dataset.name.replace(/[^A-Za-z0-9._-]+/g, '-')}-snapshot.json`;
      await window.SDDP.download(`/api/v1/projects/${encodeURIComponent(snapshot.dataset.snapshot)}/snapshot`, filename, { method: 'POST' });
      status(t('index.snapshotDownloaded'));
    } catch (error) {
      status(error.message, 'error');
    }
  });

  document.getElementById('createProject').addEventListener('submit', async event => {
    event.preventDefault();
    const input = document.getElementById('projectName');
    const name = input.value.trim();
    if (!name) return;
    try {
      const project = await window.SDDP.api('/api/v1/projects', { method: 'POST', body: { name } });
      await window.SDDP.refreshProjects();
      window.SDDP.setProject(project.id);
      input.value = '';
      render();
      status(t('index.projectCreated', { name: project.name }));
    } catch (error) {
      status(error.message, 'error');
    }
  });

  document.getElementById('importFile').addEventListener('change', async event => {
    const file = event.target.files?.[0];
    if (!file) return;
    try {
      status(t('index.importing'));
      const snapshot = window.SDDP.parseJson(await file.text(), 'snapshot');
      const project = await window.SDDP.api('/api/v1/projects/import', { method: 'POST', body: snapshot });
      await window.SDDP.refreshProjects();
      window.SDDP.setProject(project.id);
      render();
      status(t('index.projectImported', { name: project.name }));
    } catch (error) {
      status(error.message, 'error');
    } finally {
      event.target.value = '';
    }
  });

  document.getElementById('refreshProjects').addEventListener('click', async () => {
    try {
      await window.SDDP.refreshProjects();
      render();
      status(t('index.refreshed'));
    } catch (error) {
      status(error.message, 'error');
    }
  });

  window.SDDP.onContextChange(render);
  window.SDDP.onReady(render);
})();
