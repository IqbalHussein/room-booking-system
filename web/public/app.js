// Study Room Booker — schedule editor. Plain JS, no build step.

const DAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];
const DAY_LABEL = Object.fromEntries(DAYS.map((d) => [d, d[0] + d.slice(1).toLowerCase()]));
const DAY_START = 7 * 60; // grid and dropdowns run 7:00am–11:00pm
const DAY_END = 23 * 60;
const TIMES = [];
for (let m = DAY_START; m <= DAY_END; m += 30) {
  TIMES.push(`${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`);
}
const RUN_UTC_HOUR = 5;
const RUN_UTC_MINUTE = 1;
const LOOKAHEAD_DAYS = 7;
const MAX_ROOMS = 5;
const MAX_BLOCKS = 4;
const DAILY_MAX = 180; // LibCal: 3 hours per account per day
const TZ = 'America/Toronto';

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
  open: null, // expanded day
  copyFor: null,
  saving: false,
};

/* ---------------- helpers ---------------- */

const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const clone = (o) => JSON.parse(JSON.stringify(o));
const toMin = (t) => Number(t.slice(0, 2)) * 60 + Number(t.slice(3));
const toTime = (m) => `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`;
const blockMins = (b) => toMin(b.end) - toMin(b.start);
const dayMins = (d) => (d?.blocks || []).reduce((s, b) => s + blockMins(b), 0);

function fmtTime(t) {
  let h = Number(t.slice(0, 2));
  const m = t.slice(3);
  const ap = h >= 12 ? 'pm' : 'am';
  h = h % 12 || 12;
  return m === '00' ? `${h}${ap}` : `${h}:${m}${ap}`;
}

const fmtRange = (b) => `${fmtTime(b.start)}–${fmtTime(b.end)}`;

function fmtHM(mins) {
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

/* Dates are handled as YYYY-MM-DD strings, in library (Toronto) time. */
const isoIn = (date, timeZone) => new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(date);
const todayISO = () => isoIn(new Date(), TZ);
function addDays(iso, n) {
  const d = new Date(`${iso}T12:00:00Z`);
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
}
const dayKeyOf = (iso) => DAYS[(new Date(`${iso}T12:00:00Z`).getUTCDay() + 6) % 7];
const fmtISO = (iso, opts = { weekday: 'short', month: 'short', day: 'numeric' }) =>
  new Date(`${iso}T12:00:00Z`).toLocaleDateString(undefined, { ...opts, timeZone: 'UTC' });

/** The next nightly run and the date it books (7 days ahead). */
function nextRun(now = new Date()) {
  const run = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate(), RUN_UTC_HOUR, RUN_UTC_MINUTE));
  if (run <= now) run.setUTCDate(run.getUTCDate() + 1);
  const targetISO = addDays(isoIn(run, 'UTC'), LOOKAHEAD_DAYS);
  const time = run.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', timeZone: TZ });
  const hour = Number(new Intl.DateTimeFormat('en-US', { hour: 'numeric', hourCycle: 'h23', timeZone: TZ }).format(run));
  const runDay = isoIn(run, TZ);
  const today = todayISO();
  const when = runDay === today ? 'today'
    : runDay === addDays(today, 1) ? (hour < 6 ? 'tonight' : 'tomorrow')
      : fmtISO(runDay, { weekday: 'long' });
  return { run, targetISO, dayKey: dayKeyOf(targetISO), label: `${when} at ${time} ET` };
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

/* ---------------- rules (mirror lib/schedule.js) ---------------- */

/** Same-room, overlapping-time clashes with other profiles. */
function conflictsFor(schedule, claims) {
  const out = [];
  for (const day of DAYS) {
    const mine = schedule[day];
    if (!mine?.enabled) continue;
    mine.blocks.forEach((b, block) => {
      for (const c of claims) {
        if (c.day !== day || !(toMin(b.start) < toMin(c.end) && toMin(c.start) < toMin(b.end))) continue;
        b.rooms.forEach((room, myRank) => {
          const theirRank = c.rooms.findIndex((r) => r.toUpperCase() === room.toUpperCase());
          if (theirRank >= 0) out.push({ day, block, room, myRank, theirRank, ...c });
        });
      }
    });
  }
  return out;
}

/** Things that would make the server reject a save, per day. */
function problemsFor(schedule) {
  const out = {};
  for (const day of DAYS) {
    const d = schedule[day];
    if (!d.enabled) continue;
    const list = [];
    const multi = d.blocks.length > 1;
    d.blocks.forEach((b, i) => {
      if (!b.rooms.length) list.push(multi ? `Block ${i + 1} needs a room.` : 'Add a room.');
    });
    const sorted = [...d.blocks].sort((a, b) => toMin(a.start) - toMin(b.start));
    for (let i = 1; i < sorted.length; i++) {
      if (toMin(sorted[i].start) < toMin(sorted[i - 1].end)) list.push('Two blocks overlap. Move one of them.');
    }
    if (dayMins(d) > DAILY_MAX) list.push(`That's ${fmtHM(dayMins(d))}. The library allows 3h per day in total.`);
    if (list.length) out[day] = list;
  }
  return out;
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

const STATUS_LABEL = { SUCCESS: 'Booked', UNAVAILABLE: 'Room taken', FAILED: 'Failed', SKIPPED: 'Nothing scheduled' };

/** "Coming up": what is booked (from nightly results) and what tonight's run will try. */
function agendaHtml(p, next) {
  const today = todayISO();
  const rows = [];
  // Tonight's run can target today+8 in Toronto time (it books 7 days after the run's date).
  for (let k = 0; k <= LOOKAHEAD_DAYS + 1; k++) {
    const iso = addDays(today, k);
    if (iso > next.targetISO) break;
    const result = (p.lastResults || []).find((r) => r.targetDate === iso);
    const when = k === 0 ? 'Today' : k === 1 ? 'Tomorrow' : fmtISO(iso, { weekday: 'short' });
    const date = fmtISO(iso, { month: 'short', day: 'numeric' });
    if (result) {
      rows.push(`<li class="ag-row"><div class="ag-date"><strong>${when}</strong><span>${date}</span></div>
        <div class="ag-body"><span class="pill ${esc(result.status)}">${STATUS_LABEL[result.status] || esc(result.status)}</span>
        <span class="small">${esc(result.summary || '')}</span></div></li>`);
    } else if (iso === next.targetISO) {
      const d = p.schedule[next.dayKey];
      const body = p.paused
        ? '<span class="pill paused">Paused</span><span class="small muted">Nothing will be booked while paused.</span>'
        : d.enabled
          ? `<span class="pill pending">Books ${esc(next.label)}</span>
             <span class="small">${d.blocks.map((b) => `${fmtRange(b)} · ${esc(b.rooms.join(' → '))}`).join('<br>')}</span>`
          : `<span class="pill none">Nothing to book</span><span class="small muted">${DAY_LABEL[next.dayKey]} is off in your schedule.</span>`;
      rows.push(`<li class="ag-row next"><div class="ag-date"><strong>${when}</strong><span>${date}</span></div><div class="ag-body">${body}</div></li>`);
    }
  }
  return `<section class="agenda" aria-labelledby="agTitle">
    <div class="section-title"><h2 id="agTitle">Coming up</h2>
      <span class="small muted">Each night at 12:01 AM ET, the booker books 7 days ahead</span></div>
    <ul class="ag-list">${rows.join('')}</ul>
    ${rows.length === 1 && !(p.lastResults || []).length ? '<p class="small muted ag-foot">Bookings show up here as they’re made.</p>' : ''}
  </section>`;
}

/** Tiny 7am–11pm timeline for a day row: your blocks, plus amber ticks where a friend wants the same room. */
function timeline(d, conflicts) {
  const span = DAY_END - DAY_START;
  const pos = (t) => ((Math.min(Math.max(toMin(t), DAY_START), DAY_END) - DAY_START) / span) * 100;
  const bars = d.enabled ? d.blocks.map((b) => {
    const w = pos(b.end) - pos(b.start);
    return `<span class="tl-block" style="left:${pos(b.start)}%;width:${w}%">${w >= 15 ? `<span class="tl-label">${esc(b.rooms[0] || '')}</span>` : ''}</span>`;
  }).join('') : '';
  const marks = conflicts.map((c) => `<span class="tl-clash" style="left:${pos(c.start)}%;width:${pos(c.end) - pos(c.start)}%"></span>`).join('');
  return `<span class="tl" aria-hidden="true">${[9, 12, 15, 18, 21].map((h) => `<span class="tl-tick" style="left:${((h * 60 - DAY_START) / span) * 100}%"></span>`).join('')}${bars}${marks}</span>`;
}

function startOptions(selected) {
  return TIMES.filter((t) => toMin(t) <= DAY_END - 30)
    .map((t) => `<option value="${t}" ${t === selected ? 'selected' : ''}>${fmtTime(t)}</option>`).join('');
}

/** End times for a block: after its start, within the day, and within what's left of the 3h budget. */
function endOptions(d, i) {
  const b = d.blocks[i];
  const others = dayMins(d) - blockMins(b);
  const max = Math.min(DAY_END, toMin(b.start) + Math.max(30, DAILY_MAX - others));
  const opts = [];
  for (let m = toMin(b.start) + 30; m <= max; m += 30) opts.push(toTime(m));
  if (!opts.includes(b.end)) opts.push(b.end); // keep an over-budget value visible so it can be fixed
  return opts.map((t) => `<option value="${t}" ${t === b.end ? 'selected' : ''}>${fmtTime(t)} · ${fmtHM(toMin(t) - toMin(b.start))}</option>`).join('');
}

function chipsHtml(day, bi, b, clashRooms) {
  return b.rooms.map((room, i) => {
    const clash = clashRooms.has(room.toUpperCase());
    return `<span class="chip ${clash ? 'conflict' : ''}">
      <span class="rank" aria-label="choice ${i + 1}">${i + 1}</span>
      <span class="room">${esc(room)}${clash ? ' <span aria-label="wanted by someone else">⚠</span>' : ''}</span>
      <button class="chip-btn" data-act="up" data-day="${day}" data-b="${bi}" data-i="${i}" ${i === 0 ? 'disabled' : ''} aria-label="Try ${esc(room)} earlier">↑</button>
      <button class="chip-btn" data-act="down" data-day="${day}" data-b="${bi}" data-i="${i}" ${i === b.rooms.length - 1 ? 'disabled' : ''} aria-label="Try ${esc(room)} later">↓</button>
      <button class="chip-btn" data-act="rm" data-day="${day}" data-b="${bi}" data-i="${i}" aria-label="Remove ${esc(room)}">✕</button>
    </span>`;
  }).join('');
}

/** One line per other person, instead of one paragraph per room. */
function clashSummary(list) {
  const byPerson = new Map();
  for (const c of list) {
    const key = `${c.profileName}|${c.start}|${c.end}`;
    if (!byPerson.has(key)) byPerson.set(key, { ...c, rooms: [] });
    byPerson.get(key).rooms.push(c.room);
  }
  return [...byPerson.values()].map((c) => `<li>⚠ <strong>${esc(c.profileName)}</strong> also wants ${c.rooms.map(esc).join(', ')}
    ${fmtRange(c)}. <span class="muted">${c.runsFirst ? 'Their booking runs first.' : 'Yours runs first.'}</span></li>`).join('');
}

function dayCard(day, conflicts, problems) {
  const d = state.draft[day];
  const open = state.open === day;
  const mine = conflicts.filter((c) => c.day === day);
  const issues = problems[day] || [];
  const used = dayMins(d);
  const summary = d.enabled ? fmtHM(used) : 'Off';

  let body = '';
  if (open) {
    const blocks = d.blocks.map((b, bi) => {
      const clashes = mine.filter((c) => c.block === bi);
      const clashRooms = new Set(clashes.map((c) => c.room.toUpperCase()));
      return `<div class="block">
        <div class="block-head">
          <span class="block-title">${d.blocks.length > 1 ? `Block ${bi + 1}` : 'Time'}</span>
          ${d.blocks.length > 1 ? `<button class="btn ghost small" data-act="rm-block" data-day="${day}" data-b="${bi}">Remove block</button>` : ''}
        </div>
        <div class="times">
          <select data-act="start" data-day="${day}" data-b="${bi}" aria-label="${DAY_LABEL[day]} block ${bi + 1} start">${startOptions(b.start)}</select>
          <span class="muted">to</span>
          <select data-act="end" data-day="${day}" data-b="${bi}" aria-label="${DAY_LABEL[day]} block ${bi + 1} end">${endOptions(d, bi)}</select>
        </div>
        <div class="rooms-label">${b.rooms.length > 1 ? 'Rooms, tried in this order until one is free' : b.rooms.length ? 'Room. Add a backup in case it’s taken' : 'Which room?'}</div>
        <div class="chips">
          ${chipsHtml(day, bi, b, clashRooms)}
          ${b.rooms.length < MAX_ROOMS ? `<form class="add-room" data-add="${day}" data-b="${bi}">
            <input class="input" name="room" placeholder="${b.rooms.length ? '+ backup room' : '+ add room, e.g. 507'}" maxlength="24" autocomplete="off"
              aria-label="Add a room to ${DAY_LABEL[day]} block ${bi + 1}" data-focus="add-${day}-${bi}">
            <button class="btn small" type="submit">Add</button>
          </form>` : ''}
        </div>
        ${clashes.length ? `<ul class="warn">${clashSummary(clashes)}</ul>` : ''}
      </div>`;
    }).join('');

    const left = DAILY_MAX - used;
    const canAdd = d.blocks.length < MAX_BLOCKS && left >= 30;
    body = `<div class="day-body" id="body-${day}">
      <div class="budget ${used > DAILY_MAX ? 'over' : ''}">
        <div class="budget-bar"><span style="width:${Math.min(100, (used / DAILY_MAX) * 100)}%"></span></div>
        <span class="small">${fmtHM(used)} of 3h daily limit${used < DAILY_MAX ? ` · ${fmtHM(left)} left` : ''}</span>
      </div>
      ${blocks}
      ${issues.length ? `<ul class="issues" role="alert">${issues.map((x) => `<li>${esc(x)}</li>`).join('')}</ul>` : ''}
      <div class="day-foot">
        ${canAdd ? `<button class="btn small" data-act="add-block" data-day="${day}">+ Add another block</button>`
          : `<span class="small muted">${left < 30 ? 'Daily limit reached' : `Up to ${MAX_BLOCKS} blocks`}</span>`}
        ${state.copyFor === day ? '' : `<button class="btn small ghost" data-act="copy" data-day="${day}">Copy to other days…</button>`}
      </div>
      ${state.copyFor === day ? `<div class="copy-pop">
        <span class="small muted">Copy ${DAY_LABEL[day]}'s blocks and rooms to:</span>
        <div class="copy-days">${DAYS.filter((x) => x !== day).map((x) => `
          <label><input type="checkbox" value="${x}" data-copy-day> ${DAY_LABEL[x].slice(0, 3)}</label>`).join('')}</div>
        <div class="row"><button class="btn small primary" data-act="copy-apply" data-day="${day}">Apply</button>
          <button class="btn small ghost" data-act="copy-cancel">Cancel</button></div>
      </div>` : ''}
    </div>`;
  }

  return `
  <article class="day ${d.enabled ? '' : 'off'} ${open ? 'open' : ''} ${mine.length ? 'has-conflict' : ''} ${issues.length ? 'has-issue' : ''}">
    <div class="day-row">
      <label class="switch"><input type="checkbox" data-act="toggle" data-day="${day}" ${d.enabled ? 'checked' : ''} aria-label="Book on ${DAY_LABEL[day]}"><span class="track"></span></label>
      <button class="day-open" data-act="open" data-day="${day}" aria-expanded="${open}" ${open ? `aria-controls="body-${day}"` : ''}>
        <span class="day-name"><span class="long">${DAY_LABEL[day]}</span><span class="short">${DAY_LABEL[day].slice(0, 3)}</span></span>
        ${timeline(d, d.enabled ? mine : [])}
        <span class="day-sum">${issues.length ? '<span class="dot-issue" aria-label="needs attention"></span>' : mine.length ? '<span aria-label="room overlap">⚠</span> ' : ''}${summary}</span>
        <span class="chev" aria-hidden="true"></span>
      </button>
    </div>
    ${body}
  </article>`;
}

function renderEditor() {
  const p = state.profile;
  const focusKey = document.activeElement?.dataset?.focus;
  const next = nextRun();
  const conflicts = p.paused ? [] : conflictsFor(state.draft, state.claims);
  const problems = problemsFor(state.draft);
  const problemCount = Object.values(problems).flat().length;
  const active = DAYS.filter((d) => state.draft[d].enabled);
  const weekly = active.reduce((s, d) => s + dayMins(state.draft[d]), 0);

  app.innerHTML = `
    <section class="ed-head">
      ${avatar(p.name)}
      <div class="grow"><h1>${esc(p.name)}</h1>
        <p class="muted small">${active.length ? `${fmtHM(weekly)} a week across ${active.length} ${active.length === 1 ? 'day' : 'days'}` : 'No days on yet. Switch one on below to start.'}</p></div>
    </section>

    ${p.paused ? `<div class="banner"><span><strong>Bookings are paused.</strong> Nothing will be booked for you until you resume.</span>
      <button class="btn small primary" data-act="unpause">Resume</button></div>` : ''}
    ${!p.hasPassword ? `<div class="banner"><span><strong>No Carleton login saved.</strong> Add it under Settings or bookings will fail.</span></div>` : ''}

    ${agendaHtml(p, next)}

    <section aria-labelledby="wkTitle">
      <div class="section-title"><h2 id="wkTitle">Weekly schedule</h2>
        <span class="small muted">Up to 3h a day, split however you like</span></div>
      <div class="days">
        <div class="axis" aria-hidden="true"><div class="day-row"><span></span><span class="day-open">
          <span></span><span class="axis-line">${[9, 12, 15, 18, 21].map((h) =>
            `<span style="left:${((h * 60 - DAY_START) / (DAY_END - DAY_START)) * 100}%">${h > 12 ? h - 12 : h}${h >= 12 ? 'p' : 'a'}</span>`).join('')}</span>
          <span></span><span></span></span></div></div>
        ${DAYS.map((d) => dayCard(d, conflicts, problems)).join('')}
      </div>
    </section>

    <section class="settings">
      <h2>Settings</h2>
      ${p.lastResults?.length ? `<details class="card"><summary>Booking history</summary><div class="inner history">
        ${p.lastResults.map((r) => `<div class="hist-row"><div class="row"><span class="pill ${esc(r.status)}">${STATUS_LABEL[r.status] || esc(r.status)}</span>
          <span class="small muted">for ${r.targetDate ? fmtISO(r.targetDate) : '?'} · ran ${new Date(r.ranAt).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })}</span></div>
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
      <span>${problemCount ? `Fix ${problemCount} ${problemCount === 1 ? 'issue' : 'issues'} to save` : conflicts.length ? '⚠ Unsaved, with room overlaps' : 'Unsaved changes'}</span>
      <span class="row" style="gap:4px"><button class="btn ghost small" data-act="discard">Discard</button>
      <button class="btn primary" data-act="save" ${state.saving ? 'disabled' : ''}>${state.saving ? 'Saving…' : 'Save'}</button></span>
    </div>`;

  if (focusKey) app.querySelector(`[data-focus="${focusKey}"]`)?.focus();
}

function mutate(fn) {
  fn(state.draft);
  renderEditor();
}

/** Where a new block goes: an hour after the last one, else an hour before the first. */
function newBlock(d) {
  const left = DAILY_MAX - dayMins(d);
  const len = Math.min(90, left);
  const sorted = [...d.blocks].sort((a, b) => toMin(a.start) - toMin(b.start));
  const last = sorted[sorted.length - 1];
  const rooms = [...last.rooms];
  let start = toMin(last.end) + 60;
  if (start + len > DAY_END) start = toMin(sorted[0].start) - 60 - len;
  if (start < DAY_START) return null;
  return { start: toTime(start), end: toTime(start + len), rooms };
}

async function save() {
  const problems = problemsFor(state.draft);
  const firstBad = DAYS.find((d) => problems[d]);
  if (firstBad) {
    state.open = firstBad;
    renderEditor();
    document.getElementById(`body-${firstBad}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    return toast(`${DAY_LABEL[firstBad]}: ${problems[firstBad][0]}`, true);
  }
  const conflicts = state.profile.paused ? [] : conflictsFor(state.draft, state.claims);
  if (conflicts.length) {
    const ok = await modal({
      title: 'Some rooms overlap with friends',
      html: `<p style="margin-bottom:10px">Once a room is booked, nobody else can book it. These overlap:</p>
        <ul>${conflicts.map((c) => `<li>${DAY_LABEL[c.day]} ${fmtRange(state.draft[c.day].blocks[c.block])}: <strong>${esc(c.room)}</strong>, also wanted by ${esc(c.profileName)}</li>`).join('')}</ul>
        <p style="margin-top:10px">You can still save. Whoever doesn't get the room falls back to their next choice.</p>`,
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
  const bi = Number(t.dataset.b);
  const i = Number(t.dataset.i);
  if (!act) return;
  const rooms = (s) => s[day].blocks[bi].rooms;
  if (act === 'open') {
    state.open = state.open === day ? null : day;
    state.copyFor = null;
    renderEditor();
  }
  if (act === 'up') mutate((s) => rooms(s).splice(i - 1, 0, rooms(s).splice(i, 1)[0]));
  if (act === 'down') mutate((s) => rooms(s).splice(i + 1, 0, rooms(s).splice(i, 1)[0]));
  if (act === 'rm') mutate((s) => rooms(s).splice(i, 1));
  if (act === 'add-block') {
    const b = newBlock(state.draft[day]);
    if (!b) return toast('No space left in the day for another block', true);
    mutate((s) => s[day].blocks.push(b));
  }
  if (act === 'rm-block') mutate((s) => s[day].blocks.splice(bi, 1));
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
  const bi = Number(e.target.dataset.b);
  if (!act) return;
  if (act === 'toggle') {
    const on = e.target.checked;
    if (on) state.open = day;
    mutate((s) => { s[day].enabled = on; });
    if (on && !state.draft[day].blocks[0].rooms.length) app.querySelector(`[data-focus="add-${day}-0"]`)?.focus();
  }
  if (act === 'start') {
    mutate((s) => {
      const d = s[day];
      const b = d.blocks[bi];
      const others = dayMins(d) - blockMins(b);
      const len = Math.max(30, Math.min(blockMins(b), DAILY_MAX - others));
      const start = toMin(e.target.value);
      b.start = e.target.value;
      b.end = toTime(Math.min(start + len, DAY_END)); // keep the length, within the budget and the day
    });
  }
  if (act === 'end') mutate((s) => { s[day].blocks[bi].end = e.target.value; });
});

document.addEventListener('submit', async (e) => {
  e.preventDefault();
  const f = e.target;
  if (f.id === 'createForm') return submitCreate(f);
  if (f.dataset.add) {
    const day = f.dataset.add;
    const bi = Number(f.dataset.b);
    const input = f.querySelector('input');
    const room = input.value.trim().replace(/\s+/g, ' ');
    if (!room) return;
    if (!/^[A-Za-z0-9][A-Za-z0-9 .\-]{0,23}$/.test(room)) return toast('Use just the room number, e.g. 507 or 324B', true);
    const list = state.draft[day].blocks[bi].rooms;
    if (list.some((r) => r.toUpperCase() === room.toUpperCase())) return toast(`${room} is already on this list`, true);
    mutate((s) => s[day].blocks[bi].rooms.push(room.toUpperCase()));
    app.querySelector(`[data-focus="add-${day}-${bi}"]`)?.focus();
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
