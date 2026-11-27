import { handler, method, send, body } from '../../lib/http.js';
import { isSyncRequest } from '../../lib/auth.js';
import { claimRun, isDate } from '../../lib/runs.js';

// Called by the workflow before it books. 409 = this date is already handled.
export default handler(async (req, res) => {
  if (!method(req, res, ['POST'])) return;
  if (!isSyncRequest(req)) return send(res, 401, { error: 'Unauthorized.' });
  const b = body(req);
  if (!isDate(b.targetDate)) return send(res, 400, { error: 'targetDate must be YYYY-MM-DD.' });
  const result = await claimRun(b.targetDate, { force: Boolean(b.force), source: String(b.source || 'unknown').slice(0, 40) });
  return send(res, result.ok ? 200 : 409, result);
});
