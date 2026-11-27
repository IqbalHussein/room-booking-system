// A small ledger so two triggers (Vercel cron and GitHub's own schedule) can't
// both book the same night: whoever claims a target date first runs it.
import { readJson, writeJson } from './store.js';

const STALE_MS = 90 * 60_000;
const DATE = /^\d{4}-\d{2}-\d{2}$/;
export const isDate = (d) => DATE.test(String(d ?? ''));
const runPath = (date) => `runs/${date}.json`;

/** Returns { ok: true } or { ok: false, reason, run }. */
export async function claimRun(targetDate, { force = false, source = 'unknown' } = {}) {
  const now = Date.now();
  const entry = { state: 'running', startedAt: new Date(now).toISOString(), source };
  const existing = await readJson(runPath(targetDate));
  if (!existing) {
    try {
      await writeJson(runPath(targetDate), entry, { create: true });
      return { ok: true };
    } catch {
      return { ok: false, reason: 'Another run claimed this date at the same moment.' };
    }
  }
  const run = existing.data;
  const fresh = run.state === 'running' && now - Date.parse(run.startedAt) < STALE_MS;
  if (!force && (run.state === 'done' || fresh)) {
    return { ok: false, reason: run.state === 'done' ? 'Already ran for this date.' : 'A run for this date is in progress.', run };
  }
  try {
    await writeJson(runPath(targetDate), { ...entry, previous: run.state }, { ifMatch: existing.etag });
    return { ok: true };
  } catch {
    return { ok: false, reason: 'Another run claimed this date at the same moment.' };
  }
}

export async function finishRun(targetDate, failed) {
  const now = new Date().toISOString();
  if (isDate(targetDate)) {
    const existing = await readJson(runPath(targetDate));
    await writeJson(runPath(targetDate), { ...(existing?.data || {}), state: failed ? 'failed' : 'done', finishedAt: now });
  }
  await writeJson('meta/last-run.json', { at: now, targetDate: isDate(targetDate) ? targetDate : null, failed: Boolean(failed) });
}
