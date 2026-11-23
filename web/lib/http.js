export function send(res, status, body) {
  res.setHeader('Cache-Control', 'no-store');
  res.status(status).json(body);
}

export function method(req, res, allowed) {
  if (allowed.includes(req.method)) return true;
  res.setHeader('Allow', allowed.join(', '));
  send(res, 405, { error: `Use ${allowed.join(' or ')}.` });
  return false;
}

export function body(req) {
  if (req.body && typeof req.body === 'object') return req.body;
  try {
    return JSON.parse(req.body || '{}');
  } catch {
    return {};
  }
}

/** Wraps a handler so unexpected errors come back as JSON, never a stack trace. */
export function handler(fn) {
  return async (req, res) => {
    try {
      await fn(req, res);
    } catch (err) {
      console.error(err);
      send(res, 500, { error: 'Something went wrong on the server. Try again in a moment.' });
    }
  };
}
