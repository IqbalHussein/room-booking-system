import { handler, send } from '../../lib/http.js';
import { safeEqual } from '../../lib/auth.js';
import { writeJson } from '../../lib/store.js';

// Vercel Cron calls this nightly; it starts the GitHub booking workflow.
// GitHub's own schedule is unreliable (it was switched off after 60 quiet days,
// and ran hours late before that); a workflow_dispatch has neither problem.
const REPO = 'IqbalHussein/room-booking-system';
const WORKFLOW = 'nightly_booking.yml';

export default handler(async (req, res) => {
  const secret = process.env.CRON_SECRET;
  if (!secret || !safeEqual(req.headers.authorization || '', `Bearer ${secret}`)) {
    return send(res, 401, { error: 'Unauthorized.' });
  }
  const at = new Date().toISOString();
  const token = process.env.GITHUB_DISPATCH_TOKEN;
  if (!token) {
    await writeJson('meta/last-dispatch.json', { at, ok: false, error: 'GITHUB_DISPATCH_TOKEN is not set in Vercel.' });
    return send(res, 500, { error: 'GITHUB_DISPATCH_TOKEN is not set.' });
  }
  const r = await fetch(`https://api.github.com/repos/${REPO}/actions/workflows/${WORKFLOW}/dispatches`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: 'application/vnd.github+json',
      'X-GitHub-Api-Version': '2022-11-28',
      'User-Agent': 'room-booking-scheduler',
    },
    body: JSON.stringify({ ref: 'main', inputs: { source: 'vercel-cron' } }),
  });
  const ok = r.status === 204;
  const error = ok ? null : `GitHub returned ${r.status}: ${(await r.text()).slice(0, 200)}`;
  await writeJson('meta/last-dispatch.json', { at, ok, error });
  return send(res, ok ? 200 : 502, { ok, error });
});
