// Study Room Booker — schedule editor. Plain JS, no build step.

const DAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];
const DAY_LABEL = Object.fromEntries(DAYS.map((d) => [d, d[0] + d.slice(1).toLowerCase()]));
const TIMES = [];
for (let m = 7 * 60; m <= 23 * 60; m += 30) {
  TIMES.push(`${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`);
}
const RUN_UTC_HOUR = 5;
const RUN_UTC_MINUTE = 1;
const LOOKAHEAD_DAYS = 7;
const MAX_ROOMS = 5;

const app = document.getElementById('app');
const topbarRight = document.getElementById('topbarRight');

const state = {
  view: 'loading',
  profiles: [],
  selected: null, // {id, name}
  pin: '',
  lockedUntil: 0,
  token: null,
  profile: null, // server copy
  draft: null, // schedule being edited
  claims: [],
  copyFor: null,
  saving: false,
};

/* ---------------- helpers ---------------- */

const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const clone = (o) => JSON.parse(JSON.stringify(o));
const toMin = (t) => Number(t.slice(0, 2)) * 60 + Number(t.slice(3));
const ordinal = (n) => n + (['th', 'st', 'nd', 'rd'][(n % 100 - 20) % 10] || ['th', 'st', 'nd', 'rd'][n % 100] || 'th');

function fmtTime(t) {
  let h = Number(t.slice(0, 2));
  const m = t.slice(3);
  const ap = h >= 12 ? 'pm' : 'am';
  h = h % 12 || 12;
  return `${h}:${m}${ap}`;
}

function fmtDuration(start, end) {
  const mins = toMin(end) - toMin(start);
  const h = Math.floor(mins / 60);
  const m = mins % 60;
  return h && m ? `${h}h ${m}m` : h ? `${h}h` : `${m}m`;
}

function hue(name) {
  let h = 0;
  for (const c of String(name)) h = (h * 31 + c.charCodeAt(0)) % 360;
  return h;
}

const avatar = (name, cls = '') =>
  `<span class="avatar ${cls}" style="--hue:${hue(name)}" aria-hidden="true">${esc(String(name).trim()[0]?.toUpperCase() || '?')}</span>`;

function storage(fn) {
  try {
    return fn(window.sessionStorage);
  } catch {
    return null;
  }
}

/** Next nightly run and the date it books (the booker books 7 days ahead). */
function nextRun(now = new Date()) {
  const run = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate(), RUN_UTC_HOUR, RUN_UTC_MINUTE));
  if (run <= now) run.setUTCDate(run.getUTCDate() + 1);
  const target = new Date(run);
  target.setUTCDate(target.getUTCDate() + LOOKAHEAD_DAYS);
  const dayKey = DAYS[(target.getUTCDay() + 6) % 7];
  return { run, target, dayKey };
}

const fmtDate = (d) => d.toLocaleDateString(undefined, { weekday: 'short', month: 'short', day: 'numeric', timeZone: 'UTC' });

function relRun(run) {
  const time = run.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });
  const today = new Date();
  const sameDay = run.toDateString() === today.toDateString();
  const tomorrow = new Date(today);
  tomorrow.setDate(today.getDate() + 1);
  if (sameDay) return `today at ${time}`;
  if (run.toDateString() === tomorrow.toDateString()) return `${run.getHours() < 4 ? 'tonight' : 'tomorrow'} at ${time}`;
  return `${run.toLocaleDateString(undefined, { weekday: 'long' })} at ${time}`;
}

let toastTimer;
function toast(text, bad = false) {
  const el = document.getElementById('toast');
  el.textContent = text;
  el.classList.toggle('bad', bad);
  el.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove('show'), 2800);
}

async function api(path, { method = 'GET', body, auth = true } = {}) {
  const headers = { 'Content-Type': 'application/json' };
  if (auth && state.token) headers.Authorization = `Bearer ${state.token}`;
  let res;
  try {
    res = await fetch(path, { method, headers, body: body ? JSON.stringify(body) : undefined });
  } catch {
    throw Object.assign(new Error("Can't reach the server. Check your connection."), { status: 0 });
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw Object.assign(new Error(data.error || `Request failed (${res.status})`), { status: res.status, data });
  return data;
}

function modal({ title, html, confirm = 'OK', cancel = 'Cancel', danger = false }) {
  return new Promise((resolve) => {
    const back = document.createElement('div');
    back.className = 'modal-back';
    back.innerHTML = `<div class="modal" role="dialog" aria-modal="true" aria-labelledby="mTitle">
      <h2 id="mTitle">${esc(title)}</h2>
      <div class="muted">${html}</div>
      <div class="row" style="justify-content:flex-end">
        ${cancel ? `<button class="btn ghost" data-r="0">${esc(cancel)}</button>` : ''}
        <button class="btn ${danger ? 'danger' : 'primary'}" data-r="1">${esc(confirm)}</button>
      </div></div>`;
    const done = (v) => {
      back.remove();
      document.removeEventListener('keydown', onKey);
      resolve(v);
    };
    const onKey = (e) => e.key === 'Escape' && done(false);
    back.addEventListener('click', (e) => {
      if (e.target === back) done(false);
      const r = e.target.closest('[data-r]');
      if (r) done(r.dataset.r === '1');
    });
    document.addEventListener('keydown', onKey);
    document.body.appendChild(back);
    back.querySelector('[data-r="1"]').focus();
  });
}

/* ---------------- conflicts (mirrors lib/schedule.js) ---------------- */

function conflictsFor(schedule, claims) {
  const out = [];
  for (const day of DAYS) {
    const mine = schedule[day];
    if (!mine?.enabled) continue;
    for (const c of claims) {
      if (c.day !== day || !(toMin(mine.start) < toMin(c.end) && toMin(c.start) < toMin(mine.end))) continue;
      mine.rooms.forEach((room, myRank) => {
        const theirRank = c.rooms.findIndex((r) => r.toUpperCase() === room.toUpperCase());
        if (theirRank >= 0) out.push({ day, room, myRank, theirRank, ...c });
      });
    }
  }
  return out;
}

function conflictText(c) {
  const who = `<strong>${esc(c.profileName)}</strong>`;
  const order = c.runsFirst
    ? `${who}'s booking runs before yours, so if they get it you'll fall back to your next room.`
    : `Your booking runs first, so ${esc(c.profileName)} would fall back to their next room.`;
  return `⚠ ${who} also wants <strong>${esc(c.room)}</strong> ${fmtTime(c.start)}–${fmtTime(c.end)} (their ${ordinal(c.theirRank + 1)} choice, your ${ordinal(c.myRank + 1)}). ${order}`;
}

/* ---------------- navigation ---------------- */

function go(view) {
  state.view = view;
  render();
  window.scrollTo({ top: 0 });
}

async function loadProfiles() {
  state.view = 'loading';
  render();
  try {
    const { profiles } = await api('/api/profiles', { auth: false });
    state.profiles = profiles;
  } catch (e) {
    state.profiles = [];
    toast(e.message, true);
  }
  go('picker');
}

function dirty() {
  return state.profile && state.draft && JSON.stringify(state.draft) !== JSON.stringify(state.profile.schedule);
}

async function signOut() {
  if (dirty() && !(await modal({ title: 'Discard changes?', html: "You have changes you haven't saved.", confirm: 'Discard', danger: true }))) return;
  storage((s) => s.removeItem('srb.session'));
  Object.assign(state, { token: null, profile: null, draft: null, claims: [], selected: null, pin: '' });
  loadProfiles();
}

async function enterEditor(token, profile) {
  state.token = token;
  state.profile = profile;
  state.draft = clone(profile.schedule);
  state.selected = { id: profile.id, name: profile.name };
  storage((s) => s.setItem('srb.session', JSON.stringify({ token, id: profile.id })));
  go('editor');
  refreshClaims();
}

async function refreshClaims() {
  try {
    const { claims } = await api('/api/occupancy');
    state.claims = claims;
    if (state.view === 'editor') renderEditor();
  } catch (e) {
    if (e.status === 401) sessionExpired();
  }
}

function sessionExpired() {
  toast('Your session ended. Enter your passcode again.', true);
  storage((s) => s.removeItem('srb.session'));
  state.token = null;
  state.pin = '';
  go('lock');
}

/* ---------------- views ---------------- */

function render() {
  topbarRight.innerHTML = state.view === 'editor'
    ? `<button class="btn small ghost" id="signOut">Switch profile</button>`
    : '';
  if (state.view === 'loading') app.innerHTML = '<div class="spinner" aria-label="Loading"></div>';
  if (state.view === 'picker') renderPicker();
  if (state.view === 'lock') renderLock();
  if (state.view === 'create') renderCreate();
  if (state.view === 'editor') renderEditor();
}

function renderPicker() {
  const tiles = state.profiles.map((p) => `
    <button class="tile" data-pick="${esc(p.id)}">${avatar(p.name)}<span class="name">${esc(p.name)}</span></button>`).join('');
  app.innerHTML = `
    <section class="intro">
      <h1>Who's booking?</h1>
      <p class="muted">Every night the booker reserves a study room for each person, 7 days ahead. Pick your profile to change your rooms and times.</p>
    </section>
    ${state.profiles.length ? `<div class="tiles">${tiles}
      <button class="tile add" data-create><span class="plus" aria-hidden="true">+</span><span>New profile</span></button></div>`
    : `<div class="empty"><h2>No profiles yet</h2><p class="muted">Make the first one. You'll need the invite code from whoever shared this page.</p>
      <button class="btn primary" data-create>Create a profile</button></div>`}`;
}

function renderLock() {
  const p = state.selected;
  const locked = state.lockedUntil > Date.now();
  app.innerHTML = `
    <button class="btn ghost small back" data-back>← All profiles</button>
    <section class="panel narrow lock">
      ${avatar(p.name, 'lg')}
      <div><h2>Hi, ${esc(p.name)}</h2><p class="muted small">Enter your 4-digit passcode</p></div>
      <div class="dots" id="dots" aria-hidden="true">${[0, 1, 2, 3].map((i) => `<span class="dot ${i < state.pin.length ? 'on' : ''}"></span>`).join('')}</div>
      <p class="sr-only" aria-live="polite">${state.pin.length} of 4 digits entered</p>
      <p class="msg ${locked ? 'bad' : ''}" id="lockMsg">${locked ? lockText() : ''}</p>
      <div class="keypad" role="group" aria-label="Passcode keypad">
        ${[1, 2, 3, 4, 5, 6, 7, 8, 9].map((n) => `<button class="key" data-key="${n}" ${locked ? 'disabled' : ''}>${n}</button>`).join('')}
        <button class="key fn" data-key="clear" ${locked ? 'disabled' : ''}>Clear</button>
        <button class="key" data-key="0" ${locked ? 'disabled' : ''}>0</button>
        <button class="key fn" data-key="del" aria-label="Delete digit" ${locked ? 'disabled' : ''}>⌫</button>
      </div>
    </section>`;
}

function lockText() {
  const secs = Math.max(0, Math.ceil((state.lockedUntil - Date.now()) / 1000));
  const m = Math.floor(secs / 60);
  const s = secs % 60;
  return `Too many wrong tries. Try again in ${m ? `${m}m ` : ''}${s}s.`;
}

let lockTicker;
function startLockTicker() {
  clearInterval(lockTicker);
  lockTicker = setInterval(() => {
    if (state.view !== 'lock') return clearInterval(lockTicker);
    if (state.lockedUntil <= Date.now()) {
      clearInterval(lockTicker);
      state.lockedUntil = 0;
      renderLock();
      return;
    }
    const el = document.getElementById('lockMsg');
    if (el) el.textContent = lockText();
  }, 1000);
}

async function pressKey(k) {
  if (state.lockedUntil > Date.now() || state.busyPin) return;
  if (k === 'del') state.pin = state.pin.slice(0, -1);
  else if (k === 'clear') state.pin = '';
  else if (state.pin.length < 4) state.pin += k;
  renderLock();
  if (state.pin.length !== 4) return;

  state.busyPin = true;
  try {
    const { token, profile } = await api('/api/session', { method: 'POST', auth: false, body: { profileId: state.selected.id, passcode: state.pin } });
    state.pin = '';
    enterEditor(token, profile);
  } catch (e) {
    state.pin = '';
    renderLock();
    const dots = document.getElementById('dots');
    dots?.classList.add('shake');
    const msg = document.getElementById('lockMsg');
    if (e.status === 423) {
      state.lockedUntil = Date.now() + (e.data?.lockedForSeconds || 60) * 1000;
      renderLock();
      startLockTicker();
    } else if (e.status === 401 && msg) {
      msg.classList.add('bad');
      msg.textContent = `Wrong passcode. ${e.data?.triesLeft ?? ''} ${e.data?.triesLeft === 1 ? 'try' : 'tries'} left before a short lockout.`;
    } else if (e.status === 404) {
      toast(e.message, true);
      loadProfiles();
    } else if (msg) {
      msg.classList.add('bad');
      msg.textContent = e.message;
    }
  } finally {
    state.busyPin = false;
  }
}

function renderCreate() {
  app.innerHTML = `
    <button class="btn ghost small back" data-back>← All profiles</button>
    <section class="panel narrow">
      <form class="form" id="createForm" autocomplete="off">
        <div><h2>New profile</h2><p class="muted small">Takes about a minute. You can change everything later.</p></div>
        <div class="field"><label for="cInvite">Invite code</label>
          <input class="input" id="cInvite" name="inviteCode" required autocapitalize="off" spellcheck="false">
          <span class="hint">Ask whoever shared this page.</span></div>
        <div class="field"><label for="cName">Your name</label>
          <input class="input" id="cName" name="name" required maxlength="24" placeholder="e.g. Sam"></div>
        <div class="field"><label for="cPin">Pick a 4-digit passcode</label>
          <input class="input pin-input" id="cPin" name="passcode" required inputmode="numeric" pattern="\\d{4}" maxlength="4" autocomplete="new-password">
          <span class="hint">You'll type this to open your profile.</span></div>
        <div class="field"><label for="cUser">Carleton username</label>
          <input class="input" id="cUser" name="username" required autocapitalize="off" spellcheck="false" autocomplete="username"></div>
        <div class="field"><label for="cPass">Carleton password</label>
          <input class="input" id="cPass" name="password" type="password" required autocomplete="current-password"></div>
        <p class="note"><span aria-hidden="true">🔒</span><span>The booker signs in as you to make your bookings. Your login is stored encrypted and is never shown again, not even to you.</span></p>
        <p class="err" id="cErr" role="alert"></p>
        <button class="btn primary" type="submit">Create profile</button>
      </form>
    </section>`;
  document.getElementById('cInvite').focus();
}

async function submitCreate(form) {
  const data = Object.fromEntries(new FormData(form));
  const err = document.getElementById('cErr');
  if (!/^\d{4}$/.test(data.passcode)) {
    err.textContent = 'The passcode must be exactly 4 digits.';
    return;
  }
  const btn = form.querySelector('button[type=submit]');
  btn.disabled = true;
  btn.textContent = 'Creating…';
  try {
    const { token, profile } = await api('/api/profiles', { method: 'POST', auth: false, body: data });
    toast(`Welcome, ${profile.name}! Turn on the days you want.`);
    enterEditor(token, profile);
  } catch (e) {
    err.textContent = e.message;
    btn.disabled = false;
    btn.textContent = 'Create profile';
  }
}

/* ---------------- editor ---------------- */

function timeOptions(selected, { after } = {}) {
  return TIMES.filter((t) => (after ? toMin(t) > toMin(after) : toMin(t) < 23 * 60))
    .map((t) => `<option value="${t}" ${t === selected ? 'selected' : ''}>${fmtTime(t)}</option>`).join('');
}

function dayCard(day, next, conflicts) {
  const d = state.draft[day];
  const mine = conflicts.filter((c) => c.day === day);
  const conflictRooms = new Set(mine.map((c) => c.room.toUpperCase()));
  const chips = d.rooms.map((room, i) => `
    <span class="chip ${conflictRooms.has(room.toUpperCase()) ? 'conflict' : ''}" title="${conflictRooms.has(room.toUpperCase()) ? 'Someone else wants this room at an overlapping time' : ''}">
      <span class="rank" aria-label="choice ${i + 1}">${i + 1}</span>
      <span class="room">${esc(room)}</span>
      <button class="chip-btn" data-act="up" data-day="${day}" data-i="${i}" ${i === 0 ? 'disabled' : ''} aria-label="Move ${esc(room)} earlier">↑</button>
      <button class="chip-btn" data-act="down" data-day="${day}" data-i="${i}" ${i === d.rooms.length - 1 ? 'disabled' : ''} aria-label="Move ${esc(room)} later">↓</button>
      <button class="chip-btn" data-act="rm" data-day="${day}" data-i="${i}" aria-label="Remove ${esc(room)}">✕</button>
    </span>`).join('');

  const copyPop = state.copyFor === day ? `
    <div class="copy-pop">
      <span class="small muted">Copy ${DAY_LABEL[day]}'s times and rooms to:</span>
      <div class="copy-days">${DAYS.filter((x) => x !== day).map((x) => `
        <label><input type="checkbox" value="${x}" data-copy-day> ${DAY_LABEL[x].slice(0, 3)}</label>`).join('')}</div>
      <div class="row"><button class="btn small primary" data-act="copy-apply" data-day="${day}">Apply</button>
        <button class="btn small ghost" data-act="copy-cancel">Cancel</button></div>
    </div>` : '';

  return `
  <article class="day ${d.enabled ? '' : 'off'} ${d.enabled && day === next.dayKey ? 'next' : ''} ${mine.length ? 'has-conflict' : ''}" data-daycard="${day}">
    <div class="day-top">
      <label class="switch"><input type="checkbox" data-act="toggle" data-day="${day}" ${d.enabled ? 'checked' : ''} aria-label="Book on ${DAY_LABEL[day]}"><span class="track"></span></label>
      <div class="day-name">${DAY_LABEL[day]}
        ${day === next.dayKey ? `<span class="tag">Next run · ${fmtDate(next.target)}</span>` : ''}</div>
      ${d.enabled ? `<span class="dur">${fmtDuration(d.start, d.end)}</span>` : '<span class="dur">Off</span>'}
    </div>
    ${d.enabled ? `
    <div class="day-body">
      <div class="times">
        <select data-act="start" data-day="${day}" aria-label="${DAY_LABEL[day]} start time">${timeOptions(d.start)}</select>
        <span class="muted">to</span>
        <select data-act="end" data-day="${day}" aria-label="${DAY_LABEL[day]} end time">${timeOptions(d.end, { after: d.start })}</select>
      </div>
      <div>
        <div class="rooms-label">${d.rooms.length > 1 ? 'Rooms, tried in this order until one is free' : d.rooms.length ? 'Room (add backups in case it’s taken)' : 'Which room?'}</div>
        <div class="chips" style="margin-top:6px">
          ${chips}
          ${d.rooms.length < MAX_ROOMS ? `<form class="add-room" data-add="${day}">
            <input class="input" name="room" placeholder="${d.rooms.length ? '+ backup room' : '+ add room, e.g. 507'}" maxlength="24" autocomplete="off" aria-label="Add a room for ${DAY_LABEL[day]}" data-focus="add-${day}">
          </form>` : ''}
        </div>
      </div>
      ${mine.length ? `<div class="warn" role="note">${mine.map((c) => `<span>${conflictText(c)}</span>`).join('')}</div>` : ''}
      ${copyPop}
      ${state.copyFor === day ? '' : `<div class="day-foot"><button class="btn small ghost" data-act="copy" data-day="${day}">Copy to other days…</button></div>`}
    </div>` : ''}
  </article>`;
}

function renderEditor() {
  const p = state.profile;
  const focusKey = document.activeElement?.dataset?.focus;
  const next = nextRun();
  const conflicts = p.paused ? [] : conflictsFor(state.draft, state.claims);
  const last = p.lastResults?.[0];
  const nextPlan = state.draft[next.dayKey];
  const activeDays = DAYS.filter((d) => state.draft[d].enabled).length;

  app.innerHTML = `
    <section class="ed-head">
      ${avatar(p.name, 'lg')}
      <div class="grow"><h1>${esc(p.name)}'s week</h1>
        <p class="muted small">${activeDays ? `Booking ${activeDays} ${activeDays === 1 ? 'day' : 'days'} a week, 7 days ahead.` : 'No days turned on yet. Flip a switch below to start.'}</p></div>
    </section>

    ${p.paused ? `<div class="banner"><span><strong>Bookings are paused.</strong> Nothing will be booked for you until you resume.</span>
      <button class="btn small primary" data-act="unpause">Resume bookings</button></div>` : ''}
    ${!p.hasPassword ? `<div class="banner"><span><strong>No Carleton login saved.</strong> Add it under Settings or bookings will fail.</span></div>` : ''}

    <div class="strip">
      <div class="stat"><span class="label">Next run</span>
        <span class="value">${relRun(next.run)}</span>
        <span class="small muted">${p.paused ? 'Paused' : nextPlan.enabled
          ? `Books ${esc(nextPlan.rooms[0] || '—')}${nextPlan.rooms.length > 1 ? ` (+${nextPlan.rooms.length - 1} backup)` : ''} for ${fmtDate(next.target)}, ${fmtTime(nextPlan.start)}–${fmtTime(nextPlan.end)}`
          : `Nothing for ${fmtDate(next.target)}. ${DAY_LABEL[next.dayKey]} is off.`}</span></div>
      <div class="stat"><span class="label">Last result</span>
        ${last ? `<span class="pill ${esc(last.status)}">${{ SUCCESS: 'Booked', UNAVAILABLE: 'Room taken', FAILED: 'Failed', SKIPPED: 'Nothing scheduled' }[last.status] || esc(last.status)}</span>
          <span class="small muted">${esc(last.summary || '')}</span>`
        : `<span class="pill none">No runs yet</span><span class="small muted">Results show up here after the first nightly run.</span>`}</div>
    </div>

    <div class="section-title"><h2>Weekly schedule</h2>
      <span class="small muted">Rooms are booked in 30-minute steps</span></div>
    <div class="days">${DAYS.map((d) => dayCard(d, next, conflicts)).join('')}</div>

    <section class="settings">
      <h2>Settings</h2>
      ${p.lastResults?.length ? `<details class="card"><summary>Booking history</summary><div class="inner history">
        ${p.lastResults.map((r) => `<div class="hist-row"><div class="row"><span class="pill ${esc(r.status)}">${esc(r.status)}</span>
          <span class="small muted">${new Date(r.ranAt).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })}</span></div>
          <span class="small">${esc(r.summary || '')}</span>${r.detail ? `<span class="small muted">${esc(r.detail)}</span>` : ''}</div>`).join('')}
      </div></details>` : ''}
      <details class="card"><summary>Carleton login</summary><form class="inner" id="credForm" autocomplete="off">
        <div class="field"><label for="sUser">Username</label><input class="input" id="sUser" name="username" value="${esc(p.username)}" required autocapitalize="off" spellcheck="false"></div>
        <div class="field"><label for="sPass">Password</label><input class="input" id="sPass" name="password" type="password" placeholder="${p.hasPassword ? '•••••••• (saved, leave blank to keep)' : 'Enter your password'}" autocomplete="new-password"></div>
        <div><button class="btn primary" type="submit">Update login</button></div></form></details>
      <details class="card"><summary>Passcode</summary><form class="inner" id="pinForm" autocomplete="off">
        <div class="row">
          <div class="field"><label for="sCur">Current</label><input class="input pin-input" id="sCur" name="currentPasscode" inputmode="numeric" maxlength="4" required></div>
          <div class="field"><label for="sNew">New</label><input class="input pin-input" id="sNew" name="newPasscode" inputmode="numeric" maxlength="4" required></div>
        </div>
        <div><button class="btn primary" type="submit">Change passcode</button></div></form></details>
      <details class="card"><summary>Profile</summary><div class="inner">
        <form class="row" id="nameForm"><input class="input" name="name" value="${esc(p.name)}" maxlength="24" style="max-width:240px" aria-label="Profile name"><button class="btn" type="submit">Rename</button></form>
        <div class="row">
          <button class="btn" data-act="${p.paused ? 'unpause' : 'pause'}">${p.paused ? 'Resume bookings' : 'Pause all bookings'}</button>
          <button class="btn danger" data-act="delete">Delete profile</button>
        </div></div></details>
    </section>

    <div class="savebar ${dirty() ? 'show' : ''}" role="region" aria-label="Unsaved changes">
      <span>${conflicts.length ? '⚠ Unsaved, with room overlaps' : 'Unsaved changes'}</span>
      <span class="row" style="gap:4px"><button class="btn ghost small" data-act="discard">Discard</button>
      <button class="btn primary" data-act="save" ${state.saving ? 'disabled' : ''}>${state.saving ? 'Saving…' : 'Save'}</button></span>
    </div>`;

  if (focusKey) {
    const el = app.querySelector(`[data-focus="${focusKey}"]`);
    el?.focus();
  }
}

function mutate(fn) {
  fn(state.draft);
  renderEditor();
}

async function save() {
  const conflicts = conflictsFor(state.draft, state.claims);
  if (conflicts.length && !state.profile.paused) {
    const ok = await modal({
      title: 'Some rooms overlap with friends',
      html: `<p style="margin-bottom:10px">Once a room is booked, no one else can book it. These overlap:</p>
        <ul>${conflicts.map((c) => `<li>${DAY_LABEL[c.day]}: <strong>${esc(c.room)}</strong>, also wanted by ${esc(c.profileName)} (${fmtTime(c.start)}–${fmtTime(c.end)})</li>`).join('')}</ul>
        <p style="margin-top:10px">Saving is fine. Whoever loses that room falls back to their next choice.</p>`,
      confirm: 'Save anyway',
      cancel: 'Keep editing',
    });
    if (!ok) return;
  }
  state.saving = true;
  renderEditor();
  try {
    const { profile, conflicts: serverConflicts } = await api('/api/profile', { method: 'PUT', body: { schedule: state.draft } });
    state.profile = profile;
    state.draft = clone(profile.schedule);
    toast(serverConflicts?.length ? `Saved, with ${serverConflicts.length} room ${serverConflicts.length === 1 ? 'overlap' : 'overlaps'}` : 'Saved ✓');
    refreshClaims();
  } catch (e) {
    if (e.status === 401) return sessionExpired();
    toast(e.message, true);
  } finally {
    state.saving = false;
    if (state.view === 'editor') renderEditor();
  }
}

async function updateProfile(body, okText) {
  try {
    const res = await api('/api/profile', { method: 'PUT', body });
    if (res.token) {
      state.token = res.token;
      storage((s) => s.setItem('srb.session', JSON.stringify({ token: res.token, id: res.profile.id })));
    }
    const keepDraft = dirty() ? state.draft : null;
    state.profile = res.profile;
    state.draft = keepDraft || clone(res.profile.schedule);
    toast(okText);
    renderEditor();
    return true;
  } catch (e) {
    if (e.status === 401) {
      sessionExpired();
      return false;
    }
    toast(e.message, true);
    return false;
  }
}

/* ---------------- events ---------------- */

document.addEventListener('click', async (e) => {
  const t = e.target.closest('button, [data-pick], [data-create], [data-back]');
  if (!t) return;
  if (t.id === 'signOut') return signOut();
  if (t.dataset.pick) {
    state.selected = state.profiles.find((p) => p.id === t.dataset.pick);
    state.pin = '';
    state.lockedUntil = 0;
    return go('lock');
  }
  if (t.hasAttribute('data-create')) return go('create');
  if (t.hasAttribute('data-back')) return loadProfiles();
  if (t.dataset.key) return pressKey(t.dataset.key);

  const { act, day } = t.dataset;
  const i = Number(t.dataset.i);
  if (!act) return;
  if (act === 'up') mutate((s) => s[day].rooms.splice(i - 1, 0, s[day].rooms.splice(i, 1)[0]));
  if (act === 'down') mutate((s) => s[day].rooms.splice(i + 1, 0, s[day].rooms.splice(i, 1)[0]));
  if (act === 'rm') mutate((s) => s[day].rooms.splice(i, 1));
  if (act === 'copy') { state.copyFor = day; renderEditor(); }
  if (act === 'copy-cancel') { state.copyFor = null; renderEditor(); }
  if (act === 'copy-apply') {
    const targets = [...app.querySelectorAll('[data-copy-day]:checked')].map((x) => x.value);
    state.copyFor = null;
    mutate((s) => targets.forEach((x) => { s[x] = clone(s[day]); }));
    if (targets.length) toast(`Copied to ${targets.map((x) => DAY_LABEL[x].slice(0, 3)).join(', ')}. Remember to save.`);
  }
  if (act === 'discard') { state.draft = clone(state.profile.schedule); renderEditor(); }
  if (act === 'save') save();
  if (act === 'pause') updateProfile({ paused: true }, 'Bookings paused');
  if (act === 'unpause') updateProfile({ paused: false }, 'Bookings resumed');
  if (act === 'delete') {
    const ok = await modal({
      title: `Delete ${state.profile.name}?`,
      html: 'This removes the schedule and saved login for good. Bookings already made stay booked.',
      confirm: 'Delete profile',
      danger: true,
    });
    if (!ok) return;
    try {
      await api('/api/profile', { method: 'DELETE' });
      state.draft = null;
      state.profile = null;
      toast('Profile deleted');
      storage((s) => s.removeItem('srb.session'));
      state.token = null;
      loadProfiles();
    } catch (err) {
      toast(err.message, true);
    }
  }
});

document.addEventListener('change', (e) => {
  const { act, day } = e.target.dataset;
  if (!act) return;
  if (act === 'toggle') {
    mutate((s) => {
      s[day].enabled = e.target.checked;
    });
    if (e.target.checked && !state.draft[day].rooms.length) {
      app.querySelector(`[data-focus="add-${day}"]`)?.focus();
    }
  }
  if (act === 'start') {
    mutate((s) => {
      const d = s[day];
      const len = toMin(d.end) - toMin(d.start);
      d.start = e.target.value;
      if (toMin(d.end) <= toMin(d.start)) {
        // keep the same duration when the start moves past the end
        const target = Math.min(toMin(d.start) + Math.max(len, 30), 23 * 60);
        d.end = TIMES.find((t) => toMin(t) >= target) || TIMES[TIMES.length - 1];
      }
    });
  }
  if (act === 'end') mutate((s) => { s[day].end = e.target.value; });
});

document.addEventListener('submit', async (e) => {
  e.preventDefault();
  const f = e.target;
  if (f.id === 'createForm') return submitCreate(f);
  if (f.dataset.add) {
    const day = f.dataset.add;
    const input = f.querySelector('input');
    const room = input.value.trim().replace(/\s+/g, ' ');
    if (!room) return;
    if (!/^[A-Za-z0-9][A-Za-z0-9 .\-]{0,23}$/.test(room)) return toast('Use just the room number, e.g. 507 or 324B', true);
    if (state.draft[day].rooms.some((r) => r.toUpperCase() === room.toUpperCase())) return toast(`${room} is already on ${DAY_LABEL[day]}'s list`, true);
    mutate((s) => s[day].rooms.push(room.toUpperCase()));
    app.querySelector(`[data-focus="add-${day}"]`)?.focus();
    return;
  }
  if (f.id === 'credForm') {
    const data = Object.fromEntries(new FormData(f));
    if (!data.password) delete data.password;
    return updateProfile(data, 'Login updated');
  }
  if (f.id === 'pinForm') {
    const data = Object.fromEntries(new FormData(f));
    if (!/^\d{4}$/.test(data.newPasscode)) return toast('The new passcode must be exactly 4 digits.', true);
    return updateProfile(data, 'Passcode changed');
  }
  if (f.id === 'nameForm') {
    const ok = await updateProfile({ name: new FormData(f).get('name') }, 'Renamed');
    if (ok) state.selected.name = state.profile.name;
  }
});

document.addEventListener('keydown', (e) => {
  if (state.view !== 'lock' || e.metaKey || e.ctrlKey || e.altKey) return;
  if (/^\d$/.test(e.key)) pressKey(e.key);
  else if (e.key === 'Backspace') pressKey('del');
});

window.addEventListener('beforeunload', (e) => {
  if (dirty()) {
    e.preventDefault();
    e.returnValue = '';
  }
});

/* ---------------- boot ---------------- */

(async function boot() {
  const saved = storage((s) => JSON.parse(s.getItem('srb.session') || 'null'));
  if (saved?.token) {
    state.token = saved.token;
    try {
      const { profile } = await api('/api/profile');
      return enterEditor(saved.token, profile);
    } catch {
      state.token = null;
      storage((s) => s.removeItem('srb.session'));
    }
  }
  loadProfiles();
})();
