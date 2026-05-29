(function () {
  'use strict';

  const root = document.getElementById('overview-dashboard');
  if (!root) return;

  const dataUrl    = root.dataset.viewUrl + 'data';
  const refresh    = parseInt(root.dataset.refreshInterval, 10) * 1000 || 30000;
  const title      = root.dataset.dashboardTitle || 'PIPELINE OVERVIEW';
  const kiosk      = new URLSearchParams(window.location.search).has('kiosk');
  const fullscreen = root.dataset.fullscreen === 'true';
  const rawRootUrl = (document.head && document.head.dataset.rooturl) || '';
  const rootUrl    = rawRootUrl.endsWith('/') ? rawRootUrl : rawRootUrl + '/';

  if (kiosk || fullscreen) document.body.classList.add('od-kiosk');

  let lastData = null;
  let timer    = null;

  /* helpers */

  const pad = n => String(n).padStart(2, '0');

  function fmtDuration(sec) {
    if (sec == null || isNaN(sec)) return '—';
    sec = Math.round(sec);
    if (sec < 60) return sec + 's';
    const m = Math.floor(sec / 60);
    const s = sec % 60;
    if (m >= 60) {
      const h = Math.floor(m / 60);
      const mm = m % 60;
      return mm ? h + 'h ' + mm + 'm' : h + 'h';
    }
    return s ? m + 'm ' + pad(s) + 's' : m + 'm';
  }

  function fmtMsToDuration(ms) {
    if (ms == null || isNaN(ms)) return '—';
    return fmtDuration(Math.round(ms / 1000));
  }

  function fmtAge(ms) {
    if (ms == null || isNaN(ms)) return '—';
    const sec = Math.round(ms / 1000);
    if (sec < 60) return sec + 's ago';
    const m = Math.floor(sec / 60);
    if (m < 60) return m + 'm ago';
    const h = Math.floor(m / 60);
    const mm = m % 60;
    if (h < 24) return mm ? h + 'h ' + mm + 'm ago' : h + 'h ago';
    const d = Math.floor(h / 24);
    return d + 'd ago';
  }

  function fmtGreenStreak(ms) {
    if (ms == null || isNaN(ms) || ms < 0) return 'unknown';
    const sec = Math.round(ms / 1000);
    if (sec < 60) return 'green for ' + sec + 's';
    const m = Math.floor(sec / 60);
    if (m < 60) return 'green for ' + m + 'm';
    const h = Math.floor(m / 60);
    if (h < 24) return 'green for ' + h + 'h';
    const d = Math.floor(h / 24);
    return 'green for ' + d + ' day' + (d === 1 ? '' : 's');
  }

  function el(tag, attrs, children) {
    const e = document.createElement(tag);
    if (attrs) {
      for (const k in attrs) {
        if (k === 'class') e.className = attrs[k];
        else if (k === 'html') e.innerHTML = attrs[k];
        else if (k === 'text') e.textContent = attrs[k];
        else if (k.startsWith('on')) e.addEventListener(k.slice(2), attrs[k]);
        else e.setAttribute(k, attrs[k]);
      }
    }
    if (children) {
      (Array.isArray(children) ? children : [children]).forEach(c => {
        if (c == null) return;
        if (typeof c === 'string') e.appendChild(document.createTextNode(c));
        else e.appendChild(c);
      });
    }
    return e;
  }

  function escapeHtml(s) {
    if (s == null) return '';
    return String(s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  /* icons */

  const ICONS = {
    warn: '<path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/>',
    regression: '<polyline points="23 18 13.5 8.5 8.5 13.5 1 6"/><polyline points="17 18 23 18 23 12"/>',
    clock: '<circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/>',
    lock: '<rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/>',
    activity: '<polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/>',
    server: '<rect x="2" y="3" width="20" height="14" rx="2" ry="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/>',
    expand: '<polyline points="15 3 21 3 21 9"/><polyline points="9 21 3 21 3 15"/><line x1="21" y1="3" x2="14" y2="10"/><line x1="3" y1="21" x2="10" y2="14"/>',
    shrink: '<polyline points="4 14 10 14 10 20"/><polyline points="20 10 14 10 14 4"/><line x1="14" y1="10" x2="21" y2="3"/><line x1="3" y1="21" x2="10" y2="14"/>',
  };
  function svgIcon(name) {
    return '<svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">' + ICONS[name] + '</svg>';
  }

  /* clock */

  let clockEl, dateEl;
  function tickClock() {
    if (!clockEl) return;
    const now = new Date();
    clockEl.textContent = pad(now.getHours()) + ':' + pad(now.getMinutes()) + ':' + pad(now.getSeconds());
    if (dateEl) {
      const day   = ['SUN','MON','TUE','WED','THU','FRI','SAT'][now.getDay()];
      const month = ['JAN','FEB','MAR','APR','MAY','JUN','JUL','AUG','SEP','OCT','NOV','DEC'][now.getMonth()];
      dateEl.textContent = day + ' · ' + pad(now.getDate()) + ' ' + month + ' ' + now.getFullYear();
    }
  }

  /* command strip */

  function renderCommandStrip(data) {
    const s = data.summary || {};
    const downCount = s.downCount || 0;
    const unstableCount = s.unstableCount || 0;
    const allHealthy = downCount === 0 && unstableCount === 0;
    const pulseCls = allHealthy ? ' ok' : (downCount > 0 ? '' : ' warn');

    const vitals = [
      { kind: allHealthy ? 'success' : 'danger',
        value: (s.weekSuccessRate != null ? Math.round(s.weekSuccessRate) : '—'),
        unit:  (s.weekSuccessRate != null ? '%' : ''),
        label: 'Week Success' },
      { kind: 'danger',  value: downCount,                          label: 'Down' },
      { kind: 'warn',    value: unstableCount,                      label: 'Unstable' },
      { kind: 'warn',    value: s.outbreakCount || 0,               label: 'Stage Outbreaks' },
      { kind: 'od-info', value: s.queueSize || 0,
        unit: (s.avgQueueWaitMs ? ' ~ ' + fmtMsToDuration(s.avgQueueWaitMs) : ''),
        label: 'Queue · Avg Wait' },
      { kind: '',        value: (s.agentsHealthy != null ? s.agentsHealthy : '—'),
        unit: (s.agentsTotal != null ? '/' + s.agentsTotal : ''),
        label: 'Agents' },
    ];

    const vitalNodes = vitals.map(v => {
      return '<div class="vital ' + v.kind + '">' +
        '<div class="vital-value">' + escapeHtml(String(v.value)) +
          (v.unit ? '<span class="unit">' + escapeHtml(v.unit) + '</span>' : '') +
        '</div>' +
        '<div class="vital-label">' + escapeHtml(v.label) + '</div>' +
        '</div>';
    }).join('');

    const backHtml = fullscreen
      ? '<a class="cmd-back" href="' + escapeHtml(rootUrl) + '" title="Back to Jenkins">'
        + '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">'
        + '<line x1="19" y1="12" x2="5" y2="12"/><polyline points="12 19 5 12 12 5"/>'
        + '</svg><span>Jenkins</span></a>'
      : '';

    const kioskToggleHtml = fullscreen ? '' : '<a class="od-kiosk-toggle" href="' +
      escapeHtml(kioskToggleUrl()) + '" title="' +
      (kiosk ? 'Exit kiosk mode' : 'Enter kiosk mode') + '">' +
      '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">' +
      ICONS[kiosk ? 'shrink' : 'expand'] + '</svg></a>';

    return el('header', { class: 'cmd-strip', html:
      '<div class="cmd-title">' +
        backHtml +
        '<div class="cmd-pulse' + pulseCls + '"></div>' +
        '<div class="cmd-title-text">' + escapeHtml(title.toUpperCase()) + '</div>' +
      '</div>' +
      '<div class="cmd-vitals">' + vitalNodes + '</div>' +
      '<div class="cmd-clock">' +
        kioskToggleHtml +
        '<div class="cmd-time" id="od-clock">--:--:--</div>' +
        '<div class="cmd-date" id="od-date">&nbsp;</div>' +
      '</div>'
    });
  }

  function kioskToggleUrl() {
    const url = new URL(window.location.href);
    if (kiosk) url.searchParams.delete('kiosk');
    else url.searchParams.set('kiosk', '1');
    return url.pathname + (url.search ? url.search : '') + url.hash;
  }

  /* crisis zone */

  function renderOutbreakPanel(outbreaks) {
    const items = (outbreaks || []).filter(c => c && c.count >= 2);
    let body;
    if (items.length === 0) {
      body = '<div class="cluster-empty">no stage outbreaks · failures are isolated</div>';
    } else {
      const max = Math.max.apply(null, items.map(i => i.count));
      body = '<div class="cluster-list">' + items.map(c => {
        const w = max > 0 ? Math.round(c.count * 100 / max) : 0;
        const pl = (c.pipelines || []).join(' · ');
        return '<div class="cluster-item">' +
          '<div class="cluster-row">' +
            '<div class="cluster-stage">' + escapeHtml(c.stageName) + '</div>' +
            '<div class="cluster-bar"><div class="cluster-bar-fill" style="width:' + w + '%"></div></div>' +
            '<div class="cluster-count">' + c.count + '</div>' +
          '</div>' +
          '<div class="cluster-pipelines">' + escapeHtml(pl) + '</div>' +
        '</div>';
      }).join('') + '</div>';
    }

    const totalAffected = items.reduce((a, c) => a + c.count, 0);
    const meta = items.length === 0 ? '' :
      items.length + ' stage' + (items.length === 1 ? '' : 's') + ' · ' + totalAffected + ' pipelines affected';

    return el('div', { class: 'panel outbreak', html:
      '<header class="panel-header">' + svgIcon('warn') +
        '<span>Stage Outbreak</span>' +
        (meta ? '<span class="panel-meta">' + escapeHtml(meta) + '</span>' : '') +
      '</header>' + body
    });
  }

  function renderRegressionPanel(regressions) {
    const items = regressions || [];
    let body;
    if (items.length === 0) {
      body = '<div class="cluster-empty">no recent regressions</div>';
    } else {
      const r = items[0];
      const buildLink = r.buildUrl
        ? '<a href="' + escapeHtml(r.buildUrl) + '" style="color:inherit;text-decoration:none">' + escapeHtml(r.displayName) + '</a>'
        : escapeHtml(r.displayName);
      body = '<div class="regression-main">' +
        '<div class="regression-name">' + buildLink + '</div>' +
        '<div class="regression-meta">' +
          '<span class="regression-streak">' + escapeHtml(fmtGreenStreak(r.greenDurationMs)) + '</span>' +
          '<span class="regression-arrow">→</span>' +
          '<span class="regression-broke">broke ' + escapeHtml(fmtAge(Date.now() - r.brokeAtMs)) + '</span>' +
        '</div>' +
        (r.failedStage
          ? '<div class="regression-stage">at <strong>' + escapeHtml(r.failedStage) + '</strong>' +
            (r.buildNumber ? ' · build #' + r.buildNumber : '') + '</div>'
          : '') +
      '</div>';
    }

    const meta = items.length === 0 ? '' : (items.length === 1 ? 'last 24h' : items.length + ' in last 24h');

    return el('div', { class: 'panel regression', html:
      '<header class="panel-header">' + svgIcon('regression') +
        '<span>Just Regressed</span>' +
        (meta ? '<span class="panel-meta">' + escapeHtml(meta) + '</span>' : '') +
      '</header>' + body
    });
  }

  function renderCrisis(data) {
    return el('section', { class: 'crisis' }, [
      renderOutbreakPanel(data.outbreaks),
      renderRegressionPanel(data.regressions),
    ]);
  }

  /* currently broken */

  function renderBroken(data) {
    const items = data.broken || [];
    const failures = items.filter(b => b.severity === 'failure');
    const regressionNames = new Set((data.regressions || []).map(r => r.jobName));

    // Promote fresh regressions: a pipeline that just broke is more actionable
    // than one that's been broken for weeks. Show regressions first (newest first),
    // then chronic failures (longest broken first) to fill remaining slots.
    const fresh = failures.filter(b => regressionNames.has(b.jobName))
                          .sort((a, b) => (a.sinceGreenMs || 0) - (b.sinceGreenMs || 0));
    const chronic = failures.filter(b => !regressionNames.has(b.jobName))
                            .sort((a, b) => (b.sinceGreenMs || 0) - (a.sinceGreenMs || 0));
    const ordered = fresh.concat(chronic);

    const sec = el('section', { class: 'broken-section' });
    sec.appendChild(el('header', { class: 'section-header', html:
      '<span class="section-title">Currently Broken</span>' +
      '<span class="section-meta">' + ordered.length + ' total · fresh regressions first · then longest broken</span>'
    }));

    if (ordered.length === 0) {
      sec.appendChild(el('div', { class: 'broken-grid empty', text: 'all green · no pipelines broken' }));
      return sec;
    }

    const grid = el('div', { class: 'broken-grid' });
    ordered.slice(0, 3).forEach((b, i) => {
      const sev = i === 0 ? 'critical' : (i === 1 ? 'high' : 'medium');
      grid.appendChild(renderBrokenCard(b, sev));
    });
    sec.appendChild(grid);

    if (ordered.length > 3) {
      const overflow = ordered.slice(3);
      const strip = el('div', { class: 'broken-overflow-strip' });
      overflow.forEach(b => strip.appendChild(renderBrokenCompactCard(b)));
      sec.appendChild(strip);
    }
    return sec;
  }

  function renderBrokenCompactCard(b) {
    const card = el('div', { class: 'broken-compact-card' });
    const nameLink = b.buildUrl
      ? '<a href="' + escapeHtml(b.buildUrl) + '">' + escapeHtml(b.displayName) + '</a>'
      : escapeHtml(b.displayName);

    let stageInfo = '';
    if (b.failedStage && b.failedStage.name) {
      stageInfo = '<span class="bcc-stage">at ' + escapeHtml(b.failedStage.name) + '</span>';
    }
    let streak = '';
    if (b.consecutiveFailures > 0) {
      streak = '<span class="bcc-streak">' + b.consecutiveFailures + '×</span>';
    }

    card.innerHTML =
      '<div class="bcc-row1">' +
        '<span class="bcc-tag">FAILURE</span>' +
        '<span class="bcc-name">' + nameLink + '</span>' +
        streak +
      '</div>' +
      '<div class="bcc-row2">' +
        '<span class="bcc-time">' + escapeHtml(fmtMsToDuration(b.sinceGreenMs)) + ' since green</span>' +
        stageInfo +
      '</div>';
    return card;
  }

  function renderUnstableSection(data) {
    const items = data.broken || [];
    const unstable = items.filter(b => b.severity === 'unstable')
                          .sort((a, b) => (b.sinceGreenMs || 0) - (a.sinceGreenMs || 0));

    const sec = el('section', { class: 'unstable-section' + (unstable.length === 0 ? ' empty' : '') });
    sec.appendChild(el('header', { class: 'section-header', html:
      '<span class="section-title">Currently Unstable</span>' +
      '<span class="section-meta">tests failed but build completed · ' +
        unstable.length + ' pipeline' + (unstable.length === 1 ? '' : 's') + '</span>'
    }));

    const strip = el('div', { class: 'unstable-strip' });
    if (unstable.length === 0) {
      strip.appendChild(el('span', { text: 'no pipelines currently unstable' }));
    } else {
      unstable.forEach(u => strip.appendChild(renderUnstableCompactCard(u)));
    }
    sec.appendChild(strip);
    return sec;
  }

  function renderUnstableCompactCard(u) {
    const card = el('div', { class: 'unstable-compact-card' });
    const nameLink = u.buildUrl
      ? '<a href="' + escapeHtml(u.buildUrl) + '">' + escapeHtml(u.displayName) + '</a>'
      : escapeHtml(u.displayName);

    let stageInfo = '';
    if (u.failedStage && u.failedStage.name) {
      stageInfo = '<span class="ucc-stage">at ' + escapeHtml(u.failedStage.name) + '</span>';
    }
    let streak = '';
    if (u.consecutiveFailures > 0) {
      streak = '<span class="ucc-streak">' + u.consecutiveFailures + '×</span>';
    }

    card.innerHTML =
      '<div class="ucc-row1">' +
        '<span class="ucc-tag">UNSTABLE</span>' +
        '<span class="ucc-name">' + nameLink + '</span>' +
        streak +
      '</div>' +
      '<div class="ucc-row2">' +
        '<span class="ucc-time">' + escapeHtml(fmtMsToDuration(u.sinceGreenMs)) + ' since green</span>' +
        stageInfo +
      '</div>';
    return card;
  }

  function renderBrokenCard(b, severity) {
    const themeCls = (b.severity === 'unstable') ? ' theme-unstable' : ' theme-failure';
    const card = el('div', { class: 'broken-card severity-' + severity + themeCls });

    const nameLink = b.buildUrl
      ? '<a href="' + escapeHtml(b.buildUrl) + '">' + escapeHtml(b.displayName) + '</a>'
      : escapeHtml(b.displayName);
    card.appendChild(el('div', { class: 'broken-name', html: nameLink }));
    card.appendChild(el('div', { class: 'broken-time', html:
      '<span class="broken-time-value">' + escapeHtml(fmtMsToDuration(b.sinceGreenMs)) + '</span>' +
      '<span class="broken-time-label">since green</span>'
    }));

    if (b.failedStage && b.failedStage.name) {
      const fs = b.failedStage;
      let branchesHtml = '';
      if (fs.isParallel && fs.branches && fs.branches.length) {
        const failed = fs.branches.filter(x => x.status === 'FAILED' || x.status === 'fail');
        const total = fs.branches.length;
        branchesHtml = '<div class="broken-stage-branches">' +
          '<span class="broken-stage-tag">parallel · ' + failed.length + ' of ' + total + '</span>' +
          failed.map(x => '<span class="broken-stage-branch">' + escapeHtml(x.name) + '</span>').join('') +
        '</div>';
      }
      card.appendChild(el('div', { class: 'broken-stage-hero', html:
        '<div class="broken-stage-label">failed at</div>' +
        '<div class="broken-stage-value">' + escapeHtml(fs.name) + '</div>' +
        branchesHtml
      }));
    }

    const metaParts = [];
    if (b.consecutiveFailures > 0) {
      const word = b.severity === 'unstable' ? 'unstable' : 'failure';
      metaParts.push('<span class="broken-streak">' + b.consecutiveFailures + '× ' +
        (b.consecutiveFailures === 1 ? word : 'in a row') + '</span>');
    }
    if (b.lastGreenBuildNumber) {
      metaParts.push('<span class="broken-meta-text">last green: build #' + b.lastGreenBuildNumber + '</span>');
    }
    if (metaParts.length) {
      card.appendChild(el('div', { class: 'broken-meta', html: metaParts.join('') }));
    }

    if (b.history && b.history.length) {
      const hist = el('div', { class: 'broken-history' });
      b.history.slice(-15).forEach(s => {
        hist.appendChild(el('span', { class: 'hist-dot ' + (s || 'ok') }));
      });
      card.appendChild(hist);
    }

    return card;
  }

  /* pipeline health */

  function renderHealth(data) {
    const sec = el('section', { class: 'health-section' });
    const groups = data.groups || [];

    const totalActive = groups.reduce((a, g) => a + (g.pipelines || []).length, 0);
    const hidden = data.hiddenInactive || 0;
    const meta = groups.length + ' groups · ' + totalActive + ' active' +
      (hidden ? ' · ' + hidden + ' hidden (no runs in window)' : '') +
      ' · click group to expand';
    sec.appendChild(el('header', { class: 'section-header', html:
      '<span class="section-title">Pipeline Health · Latest Run</span>' +
      '<span class="section-meta">' + escapeHtml(meta) + '</span>'
    }));

    const rows = el('div', { class: 'health-rows' });
    groups.forEach(g => rows.appendChild(renderGroupBlock(g)));
    sec.appendChild(rows);
    return sec;
  }

  function summarizeGroup(group) {
    let broken = 0, unstable = 0, building = 0;
    (group.pipelines || []).forEach(p => {
      const stages = flattenStages(p.stages || []);
      let st = 'ok';
      stages.forEach(s => {
        if (s.status === 'building') st = (st === 'fail') ? 'fail' : 'building';
        else if (s.status === 'fail') st = 'fail';
        else if (s.status === 'unstable' && st !== 'fail') st = 'unstable';
      });
      if (st === 'fail')     broken++;
      if (st === 'unstable') unstable++;
      if (st === 'building') building++;
    });
    return { broken: broken, unstable: unstable, building: building };
  }

  function flattenStages(stages) {
    const out = [];
    stages.forEach(s => {
      if (s.type === 'parallel') (s.children || []).forEach(c => out.push(c));
      else out.push(s);
    });
    return out;
  }

  function renderGroupBlock(group) {
    const block = el('div', { class: 'health-group-block' });
    const summary = summarizeGroup(group);
    const mod = summary.broken > 0 ? 'bad' : summary.unstable > 0 ? 'warn' : '';

    const badges = [];
    if (summary.broken)   badges.push('<span class="group-badge bad">' + summary.broken + ' down</span>');
    if (summary.unstable) badges.push('<span class="group-badge warn">' + summary.unstable + ' unstable</span>');
    if (summary.building) badges.push('<span class="group-badge build">' + summary.building + ' building</span>');

    const row = el('div', { class: 'health-row' + (mod ? ' ' + mod : ''),
      html:
        '<div class="health-group">' +
          '<span class="chevron">›</span>' +
          '<span>' + escapeHtml(group.name) + '</span>' +
          '<span class="health-count">' + (group.pipelines || []).length + '</span>' +
          badges.join('') +
        '</div>'
    });
    row.addEventListener('click', () => block.classList.toggle('expanded'));
    block.appendChild(row);

    const sublist = el('div', { class: 'health-sublist' });
    (group.pipelines || []).forEach(p => sublist.appendChild(renderPipelineRow(p)));
    block.appendChild(sublist);
    return block;
  }

  function renderPipelineRow(p) {
    const row = el('div', { class: 'pipeline-row' });
    const nameLink = p.buildUrl
      ? '<a href="' + escapeHtml(p.buildUrl) + '">' + escapeHtml(p.displayName || p.jobName) + '</a>'
      : escapeHtml(p.displayName || p.jobName);

    let dotCls = 'ok';
    let dotTitle = 'Last build: SUCCESS';
    const br = (p.buildResult || '').toUpperCase();
    if (br === 'FAILURE')   { dotCls = 'fail';     dotTitle = 'Last build: FAILURE'; }
    else if (br === 'UNSTABLE') { dotCls = 'unstable'; dotTitle = 'Last build: UNSTABLE (test failures)'; }
    else if (br === 'BUILDING') { dotCls = 'building'; dotTitle = 'Last build: BUILDING'; }
    else if (br === 'ABORTED')  { dotCls = 'skipped';  dotTitle = 'Last build: ABORTED'; }

    const failPct = (p.totalBuilds7d > 0)
        ? Math.round(((p.failureCount7d || 0) + (p.unstableCount7d || 0)) * 100 / p.totalBuilds7d)
        : 0;
    let metaBadge = '';
    if (p.totalBuilds7d >= 3 && failPct >= 40) {
      metaBadge = '<span class="row-flaky" title="' + (p.failureCount7d + p.unstableCount7d) + ' of ' + p.totalBuilds7d + ' recent builds failed">' + failPct + '%</span>';
    }

    row.appendChild(el('div', { class: 'pipeline-name', html:
      '<span class="row-build-dot ' + dotCls + '" title="' + escapeHtml(dotTitle) + '"></span>' +
      nameLink +
      metaBadge
    }));
    row.appendChild(buildStageDiagram(p.stages || []));
    row.appendChild(buildExecGraph(p.execTimes || []));
    return row;
  }

  /* stage graph */

  function shortStageName(name) {
    if (!name) return '';
    return String(name)
      .replace(/^Declarative:\s+/i, '')
      .replace(/^Stage:\s+/i, '');
  }
  function truncateStr(s, n) {
    if (!s) return '';
    return s.length <= n ? s : s.substring(0, n - 1) + '…';
  }

  function buildStageDiagram(stages) {
    const wrap = el('div', { class: 'pipeline-stages' });
    if (!stages || stages.length === 0) return wrap;
    wrap.innerHTML = renderPipelineGraph(stages);
    wrap.querySelectorAll('[data-tip]').forEach(node => {
      node.addEventListener('mouseenter', e => showStageTip(node, e));
      node.addEventListener('mousemove',  e => showStageTip(node, e));
      node.addEventListener('mouseleave', hideStageTip);
    });
    return wrap;
  }

  function renderPipelineGraph(stages) {
    const NODE_R = 7.5;
    const ROW_H = 26;
    const SUB_GAP = 28;
    const COL_GAP = 34;
    const TOP_PAD = 16;
    const BOTTOM_PAD = 16;
    const PADDING_X = 10;
    const LABEL_FS = 9;
    const LABEL_GAP = 5;
    const MAX_LABEL_CHARS = 12;

    // Normalize every column to { type, branches: [ {nodes: [{name,status}]} ] }.
    // A seq stage is one branch with one node; a parallel branch keeps its inner sequence.
    const cols = stages.map(s => {
      if (s.type === 'parallel') {
        const branches = (s.children || []).map(b => ({
          nodes: (b.stages && b.stages.length) ? b.stages : [{ name: b.name, status: b.status }],
        }));
        return { type: 'par', branches };
      }
      return { type: 'seq', branches: [{ nodes: [s] }] };
    });

    const branchCount = c => Math.max(1, c.branches.length);
    const colSpan = c => Math.max(1, ...c.branches.map(b => b.nodes.length));

    let x = PADDING_X;
    cols.forEach(c => { c.x = x; x += colSpan(c) * SUB_GAP + COL_GAP; });
    const totalWidth = x - COL_GAP + PADDING_X;

    const maxBranches = Math.max(1, ...cols.map(branchCount));
    const innerH = maxBranches * ROW_H;
    const totalHeight = TOP_PAD + innerH + BOTTOM_PAD;
    const centerY = TOP_PAD + innerH / 2;

    const nodeX = (c, k) => c.x + k * SUB_GAP + SUB_GAP / 2;

    // Resolve each column into placed branch rows with absolute node coordinates.
    const placed = cols.map(c => {
      const bn = c.branches.length;
      const startY = bn <= 1 ? centerY : TOP_PAD + (maxBranches - bn) * ROW_H / 2 + ROW_H / 2;
      const branches = c.branches.map((b, bi) => {
        const y = bn <= 1 ? centerY : startY + bi * ROW_H;
        const nodes = b.nodes.map((s, k) => ({
          x: nodeX(c, k), y, status: (s.status || 'ok').toLowerCase(), name: s.name || '',
        }));
        return { y, nodes };
      });
      return { type: c.type, branches };
    });

    let svg = '<svg viewBox="0 0 ' + totalWidth + ' ' + totalHeight +
      '" preserveAspectRatio="xMinYMid meet" class="pg-svg" ' +
      'style="height:' + totalHeight + 'px; width:' + totalWidth + 'px; max-width:none">';

    function drawLine(x1, y1, x2, y2, cls) {
      if (y1 === y2) {
        return '<path d="M ' + x1.toFixed(1) + ' ' + y1 + ' L ' + x2.toFixed(1) + ' ' + y2 + '" class="pg-line' + cls + '"/>';
      }
      const mx = (x1 + x2) / 2;
      return '<path d="M ' + x1.toFixed(1) + ' ' + y1 +
        ' C ' + mx.toFixed(1) + ' ' + y1 + ', ' + mx.toFixed(1) + ' ' + y2 +
        ', ' + x2.toFixed(1) + ' ' + y2 + '" class="pg-line' + cls + '"/>';
    }

    const allSkipped = br => br.nodes.every(n => n.status === 'skipped');

    // Lines between consecutive nodes within a branch.
    placed.forEach(p => p.branches.forEach(br => {
      const cls = allSkipped(br) ? ' skipped' : '';
      for (let k = 1; k < br.nodes.length; k++) {
        svg += drawLine(br.nodes[k - 1].x + NODE_R, br.nodes[k - 1].y, br.nodes[k].x - NODE_R, br.nodes[k].y, cls);
      }
    }));

    // Connectors between columns: last node of each prev branch -> first node of each curr branch.
    for (let i = 1; i < placed.length; i++) {
      const exits = placed[i - 1].branches.map(br => ({ x: br.nodes[br.nodes.length - 1].x, y: br.y, skipped: allSkipped(br) }));
      const entries = placed[i].branches.map(br => ({ x: br.nodes[0].x, y: br.y, skipped: allSkipped(br) }));
      const link = (a, b) => drawLine(a.x + NODE_R, a.y, b.x - NODE_R, b.y, (a.skipped && b.skipped) ? ' skipped' : '');
      if (exits.length === 1 && entries.length === 1) {
        svg += link(exits[0], entries[0]);
      } else if (exits.length === 1) {
        entries.forEach(e => { svg += link(exits[0], e); });
      } else if (entries.length === 1) {
        exits.forEach(e => { svg += link(e, entries[0]); });
      } else {
        const n = Math.min(exits.length, entries.length);
        for (let j = 0; j < n; j++) svg += link(exits[j], entries[j]);
      }
    }

    // Nodes + glyphs + labels.
    placed.forEach(p => p.branches.forEach(br => br.nodes.forEach(n => {
      svg += '<circle cx="' + n.x + '" cy="' + n.y + '" r="' + NODE_R +
        '" class="pg-node pg-' + n.status + '" data-tip="<strong>' + escapeHtml(n.name) +
        '</strong><span class=\'tt-label\'>' + n.status.toUpperCase() + '</span>"/>' +
        statusGlyph(n.x, n.y, n.status);

      const labelY = p.type === 'seq' ? n.y - NODE_R - LABEL_GAP : n.y + NODE_R + LABEL_GAP + LABEL_FS - 2;
      svg += '<text x="' + n.x + '" y="' + labelY + '" text-anchor="middle" class="pg-label ' +
        n.status + '" font-size="' + LABEL_FS + '">' +
        escapeHtml(truncateStr(shortStageName(n.name), MAX_LABEL_CHARS)) + '</text>';
    })));

    svg += '</svg>';
    return svg;
  }

  function statusGlyph(cx, cy, status) {
    if (status === 'ok') {
      return '<path d="M ' + (cx - 3) + ' ' + cy + ' l 2 2.4 l 4 -4.8" class="pg-glyph"/>';
    }
    if (status === 'fail') {
      return '<path d="M ' + (cx - 2.6) + ' ' + (cy - 2.6) + ' l 5.2 5.2 M ' + (cx + 2.6) + ' ' + (cy - 2.6) + ' l -5.2 5.2" class="pg-glyph"/>';
    }
    if (status === 'unstable') {
      return '<path d="M ' + cx + ' ' + (cy - 3.2) + ' l 0 3.4 M ' + cx + ' ' + (cy + 2.8) + ' l 0 0.4" class="pg-glyph"/>';
    }
    return '';
  }

  /* stage tooltip */
  let _stageTipEl = null;
  function _ensureStageTip() {
    if (_stageTipEl) return _stageTipEl;
    _stageTipEl = document.createElement('div');
    _stageTipEl.style.cssText = 'position:fixed;pointer-events:none;z-index:200;'
      + 'background:var(--od-surface-3);border:1px solid var(--od-border-strong);'
      + 'color:var(--od-text);padding:5px 9px;border-radius:5px;'
      + 'font-family:var(--od-mono);font-size:10.5px;font-weight:600;'
      + 'white-space:nowrap;box-shadow:0 6px 16px rgba(0,0,0,0.6);'
      + 'opacity:0;transition:opacity 0.1s;';
    document.body.appendChild(_stageTipEl);
    return _stageTipEl;
  }
  function showStageTip(node, e) {
    const t = _ensureStageTip();
    t.innerHTML = node.dataset.tip;
    t.style.opacity = '1';
    const rect = t.getBoundingClientRect();
    let x = e.clientX - rect.width / 2;
    let y = e.clientY - rect.height - 12;
    if (x < 8) x = 8;
    if (x + rect.width > window.innerWidth - 8) x = window.innerWidth - rect.width - 8;
    if (y < 8) y = e.clientY + 16;
    t.style.left = x + 'px';
    t.style.top = y + 'px';
  }
  function hideStageTip() {
    if (_stageTipEl) _stageTipEl.style.opacity = '0';
  }

  /* exec time graph */

  function trendOf(times) {
    if (times.length < 6) return 0;
    const half = Math.floor(times.length / 2);
    const oldArr = times.slice(0, half);
    const newArr = times.slice(half);
    const oldAvg = oldArr.reduce((a, b) => a + b, 0) / oldArr.length;
    const newAvg = newArr.reduce((a, b) => a + b, 0) / newArr.length;
    return (newAvg - oldAvg) / oldAvg;
  }

  function buildExecGraph(times) {
    const wrap = el('div', { class: 'exec-time' });
    if (!times || times.length === 0) {
      wrap.appendChild(el('div', { class: 'exec-empty', text: 'no successful runs in window' }));
      return wrap;
    }

    const w = 240, h = 56, pad = 4;
    const min = Math.min.apply(null, times);
    const max = Math.max.apply(null, times);
    const range = Math.max(max - min, 1);
    const points = times.map((v, i) => [
      pad + (i / Math.max(times.length - 1, 1)) * (w - 2 * pad),
      h - pad - ((v - min) / range) * (h - 2 * pad),
    ]);
    const linePath = 'M ' + points.map(p => p[0].toFixed(1) + ',' + p[1].toFixed(1)).join(' L ');
    const areaPath = linePath + ' L ' + (w - pad).toFixed(1) + ',' + (h - pad).toFixed(1) +
      ' L ' + pad.toFixed(1) + ',' + (h - pad).toFixed(1) + ' Z';
    const last = points[points.length - 1];

    const t = trendOf(times);
    let strokeColor = 'rgba(94, 179, 255, 0.95)';
    let fillColor   = 'rgba(94, 179, 255, 0.16)';
    let trendCls    = 'flat';
    let trendArrow  = '·';
    let trendTxt    = '';
    if (t > 0.15) {
      strokeColor = 'rgba(255, 176, 32, 1)';
      fillColor   = 'rgba(255, 176, 32, 0.16)';
      trendCls    = 'up';
      trendArrow  = '↑';
      trendTxt    = Math.round(t * 100) + '%';
    } else if (t < -0.10) {
      strokeColor = 'rgba(34, 211, 94, 1)';
      fillColor   = 'rgba(34, 211, 94, 0.16)';
      trendCls    = 'down';
      trendArrow  = '↓';
      trendTxt    = Math.abs(Math.round(t * 100)) + '%';
    }

    const avg = Math.round(times.reduce((a, b) => a + b, 0) / times.length);
    const trendChip = trendTxt
      ? '<span class="exec-trend ' + trendCls + '">' + trendArrow + ' ' + trendTxt + '</span>'
      : '';

    wrap.innerHTML =
      '<div class="exec-spark">' +
        '<svg viewBox="0 0 ' + w + ' ' + h + '" preserveAspectRatio="none">' +
          '<path d="' + areaPath + '" fill="' + fillColor + '"/>' +
          '<path d="' + linePath + '" fill="none" stroke="' + strokeColor + '" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>' +
          '<circle cx="' + last[0].toFixed(1) + '" cy="' + last[1].toFixed(1) + '" r="2.6" fill="' + strokeColor + '"/>' +
          '<line class="hover-line" x1="0" y1="0" x2="0" y2="' + h + '" stroke="' + strokeColor + '" stroke-width="0.8" stroke-dasharray="2 2" opacity="0" pointer-events="none"/>' +
          '<circle class="hover-dot" r="3.2" fill="' + strokeColor + '" opacity="0" pointer-events="none"/>' +
        '</svg>' +
        '<div class="exec-tooltip"></div>' +
      '</div>' +
      '<div class="exec-meta">' +
        '<div class="exec-value">' + escapeHtml(fmtDuration(avg)) + '</div>' +
        '<div class="exec-sub">avg ' + trendChip + '</div>' +
        '<div class="exec-range">' + escapeHtml(fmtDuration(min)) + ' – ' + escapeHtml(fmtDuration(max)) + '</div>' +
      '</div>';

    const spark = wrap.querySelector('.exec-spark');
    const hoverLine = spark.querySelector('.hover-line');
    const hoverDot  = spark.querySelector('.hover-dot');
    const tooltip   = spark.querySelector('.exec-tooltip');

    spark.addEventListener('mousemove', (e) => {
      const rect = spark.getBoundingClientRect();
      const xRel = e.clientX - rect.left;
      const ratio = Math.max(0, Math.min(1, xRel / Math.max(rect.width, 1)));
      const idx = Math.round(ratio * (times.length - 1));
      const pt = points[idx];
      const dur = times[idx];
      const fromEnd = times.length - 1 - idx;
      const ageLabel = fromEnd === 0 ? 'latest'
                     : fromEnd === 1 ? '1 build ago'
                     : fromEnd + ' builds ago';

      hoverLine.setAttribute('x1', pt[0].toFixed(1));
      hoverLine.setAttribute('x2', pt[0].toFixed(1));
      hoverLine.setAttribute('opacity', '0.55');
      hoverDot.setAttribute('cx', pt[0].toFixed(1));
      hoverDot.setAttribute('cy', pt[1].toFixed(1));
      hoverDot.setAttribute('opacity', '1');

      const xPx = (pt[0] / w) * rect.width;
      tooltip.innerHTML = '<span class="tt-dur">' + escapeHtml(fmtDuration(dur)) + '</span><span class="tt-sub">' + ageLabel + '</span>';
      tooltip.style.display = 'block';
      const ttHalf = tooltip.offsetWidth / 2;
      const clamped = Math.max(ttHalf, Math.min(rect.width - ttHalf, xPx));
      tooltip.style.left = clamped + 'px';
    });

    spark.addEventListener('mouseleave', () => {
      hoverLine.setAttribute('opacity', '0');
      hoverDot.setAttribute('opacity', '0');
      tooltip.style.display = 'none';
    });

    return wrap;
  }

  /* capacity */

  function renderCapacity(data) {
    return el('section', { class: 'capacity' }, [
      renderQueuePanel(data),
      renderLocksPanel(data.locks || []),
    ]);
  }

  function renderQueuePanel(data) {
    const s = data.summary || {};
    const queueSize = s.queueSize || 0;
    const avgWaitMs = s.avgQueueWaitMs || 0;
    const history = data.queueHistory || [];

    let valueCls = '';
    if (avgWaitMs > 30 * 60 * 1000) valueCls = ' crit';
    else if (avgWaitMs > 5 * 60 * 1000) valueCls = ' warn';

    const sparkSvg = renderQueueSparkline(history);

    return el('div', { class: 'panel', html:
      '<header class="panel-header" style="color: var(--od-blue)">' + svgIcon('clock') +
        '<span>Build Queue</span>' +
        '<span class="panel-meta">' + (history.length ? 'last ' + history.length + ' samples' : 'live') + '</span>' +
      '</header>' +
      '<div class="queue-body">' +
        '<div class="queue-numbers">' +
          '<div class="queue-row"><span class="queue-value' + valueCls + '">' + queueSize + '</span><span class="queue-label">waiting</span></div>' +
          '<div class="queue-row"><span class="queue-value sm">' + escapeHtml(fmtMsToDuration(avgWaitMs)) + '</span><span class="queue-label">avg wait</span></div>' +
        '</div>' +
        '<div class="queue-spark">' + sparkSvg + '</div>' +
      '</div>'
    });
  }

  function renderQueueSparkline(data) {
    if (!data || data.length < 2) return '';
    const w = 200, h = 60, pad = 4;
    const max = Math.max.apply(null, data.concat([1]));
    const points = data.map((v, i) =>
      (pad + (i / (data.length - 1)) * (w - 2 * pad)).toFixed(1) + ',' +
      (h - pad - (v / max) * (h - 2 * pad)).toFixed(1)
    );
    const linePath = 'M ' + points.join(' L ');
    const areaPath = linePath + ' L ' + (w - pad) + ',' + (h - pad) + ' L ' + pad + ',' + (h - pad) + ' Z';
    return '<svg viewBox="0 0 ' + w + ' ' + h + '" preserveAspectRatio="none">' +
      '<path d="' + areaPath + '" fill="rgba(94, 179, 255, 0.20)"/>' +
      '<path d="' + linePath + '" fill="none" stroke="#5eb3ff" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>' +
      '<circle cx="' + points[points.length - 1].split(',')[0] + '" cy="' + points[points.length - 1].split(',')[1] + '" r="3" fill="#5eb3ff"/>' +
    '</svg>';
  }

  function renderLocksPanel(locks) {
    let body;
    if (!locks || locks.length === 0) {
      body = '<div class="panel-empty">no lockable resources configured</div>';
    } else {
      const sorted = locks.slice().sort((a, b) => {
        const order = { crit: 0, warn: 1, active: 2, free: 3 };
        return (order[lockClass(a)] || 9) - (order[lockClass(b)] || 9);
      });
      body = '<div class="locks-list">' + sorted.map(l => {
        const cls = lockClass(l);
        const status = l.status === 'free' ? 'free' :
                       cls === 'crit' ? 'held · stale' :
                       cls === 'warn' ? 'held · long' : 'held';
        const dur = l.status === 'free' ? '—' : fmtMsToDuration(l.holdMs);
        return '<div class="lock-row ' + cls + '">' +
          '<div class="lock-name">' + escapeHtml(l.name) + '</div>' +
          '<div class="lock-status">' + escapeHtml(status) + '</div>' +
          '<div class="lock-duration">' + escapeHtml(dur) + '</div>' +
        '</div>';
      }).join('') + '</div>';
    }
    const heldCount = (locks || []).filter(l => l.status !== 'free').length;
    const freeCount = (locks || []).length - heldCount;
    const meta = locks && locks.length
      ? heldCount + ' held · ' + freeCount + ' free'
      : '';
    return el('div', { class: 'panel', html:
      '<header class="panel-header" style="color: var(--od-text)">' + svgIcon('lock') +
        '<span>Lockable Resources</span>' +
        (meta ? '<span class="panel-meta">' + escapeHtml(meta) + '</span>' : '') +
      '</header>' + body
    });
  }

  function lockClass(l) {
    if (!l || l.status === 'free') return 'free';
    const ms = l.holdMs || 0;
    if (ms > 30 * 60 * 1000) return 'crit';
    if (ms > 15 * 60 * 1000) return 'warn';
    return 'active';
  }

  /* bottom strip */

  function renderBottomStrip(data) {
    return el('footer', { class: 'bottom-strip' }, [
      renderUnstablePanel(data.unstable || []),
      renderAgentsPanel(data.agents || {}),
    ]);
  }

  function renderUnstablePanel(unstable) {
    let body;
    if (!unstable.length) {
      body = '<div class="panel-empty">no unstable pipelines</div>';
    } else {
      body = '<div class="unstable-list">' + unstable.slice(0, 6).map(u => {
        return '<div class="unstable-row">' +
          '<span class="unstable-name">' + escapeHtml(u.displayName || u.jobName) + '</span>' +
          '<span class="unstable-rate">' + u.unstableCount + ' of last ' + u.totalCount + '</span>' +
        '</div>';
      }).join('') + '</div>';
    }
    return el('div', { class: 'panel unstable-panel', html:
      '<header class="panel-header compact">' + svgIcon('activity') +
        '<span>Unstable · ' + unstable.length + '</span>' +
      '</header>' + body
    });
  }

  function renderAgentsPanel(agents) {
    const perm = agents.permanent || [];
    const clouds = agents.clouds || [];
    const allOk = perm.every(a => a.status === 'ok');

    const permHtml = perm.map(a => {
      const cls = a.status === 'down' ? 'down' : (a.status === 'partial' ? 'partial' : 'ok');
      const href = rootUrl + 'computer/' + encodeURIComponent(a.name) + '/';
      return '<a class="agent ' + cls + '" href="' + escapeHtml(href) +
        '" title="Open ' + escapeHtml(a.name) + '"><span class="agent-dot"></span>' +
        escapeHtml(a.name) + '</a>';
    }).join('');

    const cloudsHtml = clouds.map(c => {
      return '<div class="agent-cloud">' +
        svgIcon('server') +
        '<span class="cloud-name">' + escapeHtml(c.name) + '</span>' +
      '</div>';
    }).join('');

    const meta = perm.length || clouds.length ? (allOk ? 'all healthy' : 'check status') : '';

    return el('div', { class: 'panel', html:
      '<header class="panel-header compact" style="color: var(--od-text)">' + svgIcon('server') +
        '<span>Agents</span>' +
        (meta ? '<span class="panel-meta">' + escapeHtml(meta) + '</span>' : '') +
      '</header>' +
      '<div class="agents-list">' + permHtml + cloudsHtml +
        (perm.length === 0 && clouds.length === 0 ? '<div class="panel-empty">no agents reported</div>' : '') +
      '</div>'
    });
  }

  /* full render */

  function renderAll(data) {
    lastData = data;
    root.innerHTML = '';
    const dash = el('div', { class: 'dashboard' });
    dash.appendChild(renderCommandStrip(data));
    dash.appendChild(renderCrisis(data));
    dash.appendChild(renderBroken(data));
    dash.appendChild(renderUnstableSection(data));
    dash.appendChild(renderHealth(data));
    dash.appendChild(renderCapacity(data));
    dash.appendChild(renderBottomStrip(data));
    root.appendChild(dash);

    clockEl = document.getElementById('od-clock');
    dateEl  = document.getElementById('od-date');
    tickClock();
  }

  function renderError(msg) {
    root.innerHTML = '';
    root.appendChild(el('div', { class: 'od-error', text: 'Failed to load dashboard: ' + msg }));
  }

  /* fetch loop */

  function fetchData() {
    fetch(dataUrl, {
      method: 'POST',
      headers: crumb.wrap({ Accept: 'application/json' }),
    })
      .then(resp => {
        if (!resp.ok) throw new Error('HTTP ' + resp.status);
        return resp.json();
      })
      .then(data => {
        if (data.error) renderError(data.error);
        else renderAll(data);
      })
      .catch(e => renderError(e.message));
  }

  /* boot */

  fetchData();
  setInterval(fetchData, refresh);
  setInterval(tickClock, 1000);

})();
