#!/usr/bin/env node
/**
 * Loopback-only, synthetic DeepSeek Harness rc.2 upstream for Android QA.
 * No real user/session data, credentials, bridge pair codes, or tool execution.
 * Implements the small native /api RPC + /api/remote.mux surface needed by
 * the mobile client and interaction QA. Uses Node built-ins only.
 */
import http from 'node:http';
import crypto from 'node:crypto';

const HOST = '127.0.0.1';
const PORT = 3082;
const WS_PATH = '/api/remote.mux';
const ADMIN_PREFIX = '/__qa/';
const NOW = Date.now();
const sessions = new Map([
  ['qa-session-a', makeSession('qa-session-a', 'QA Session A')],
  ['qa-session-b', makeSession('qa-session-b', 'QA Session B')],
]);
const pending = new Map();
const clients = new Set();
const outcomes = [];
const streams = new Set();
let eventOrdinal = 0;
let wsOrdinal = 0;

function makeSession(sessionId, title) {
  return {
    sessionId, title, cursor: 1, records: [{ type: 'event', event: {
      seq: 1, time: NOW, type: 'user/message', surfaceOp: 'append',
      data: { source: { kind: 'user' }, content: [{ type: 'text', text: 'Synthetic QA fixture session.' }] },
    } }],
    values: {
      title,
      permissions: { options: [
        { value: 'read-only', name: 'Read Only', description: 'Synthetic fixture' },
        { value: 'workspace-write', name: 'Workspace Write', description: 'Synthetic fixture' },
        { value: 'danger-full-access', name: 'Full Access', description: 'Synthetic fixture' },
      ], currentValue: 'workspace-write' },
      plan: { active: false, pending: false },
      userQuestions: { active: [], settled: [] },
    },
    projectionsAsOfSeq: 1,
  };
}
function json(res, status, value) {
  const body = Buffer.from(JSON.stringify(value));
  res.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': body.length, 'Cache-Control': 'no-store' });
  res.end(body);
}
function response(rpcId, value, ok = true, error = undefined) {
  return { type: 'server-response', rpcId, result: ok ? { ok: true, value } : { ok: false, error } };
}
function assertLoopback(req) {
  const address = req.socket.remoteAddress || '';
  return address === '127.0.0.1' || address === '::1' || address === '::ffff:127.0.0.1';
}
function sessionView(session) {
  return {
    sessionId: session.sessionId,
    ...(session.origin ? { origin: session.origin } : {}),
    ...(session.parentSessionId ? { parentSessionId: session.parentSessionId } : {}),
    cwd: '/tmp/dsh-interaction-qa-20261010/workspace',
    updatedAt: NOW,
    running: true,
    blank: false,
    projections: { asOfSeq: session.projectionsAsOfSeq, values: session.values },
  };
}
function snapshot(session) {
  return {
    type: 'snapshot', cursor: session.cursor, records: session.records,
    hasMore: false,
    projections: { asOfSeq: session.projectionsAsOfSeq, values: session.values },
    assistantStream: { revision: 0 },
  };
}
function itemFrame(streamId, value) {
  return { type: 'item', streamId, value };
}
function sendFrame(client, frame) {
  if (client.closed) return;
  client.writeText(JSON.stringify(frame));
}
function deliver(client, event) {
  if (event.type === 'waterfall') {
    const entry = pending.get(event.eventId);
    if (entry && client.clientId) entry.deliveredClientIds.add(client.clientId);
  }
  sendFrame(client, itemFrame(client.eventStreamId, event));
}
function publishQuestionProjection(session) {
  const value = session.values.userQuestions;
  for (const stream of streams) {
    if (stream.endpoint === 'session/control') {
      sendFrame(stream.client, itemFrame(stream.id, {
        type: 'projection', sessionId: session.sessionId, key: 'userQuestions', value,
        seq: session.projectionsAsOfSeq,
      }));
    }
  }
}
function endWaitStreams(sessionId, callId) {
  for (const stream of [...streams]) {
    if (stream.endpoint === 'userQuestions/attachWait' && stream.sessionId === sessionId && stream.callId === callId) {
      sendFrame(stream.client, { type: 'end', streamId: stream.id });
      streams.delete(stream);
    }
  }
}
function cancelWaterfall(entry) {
  pending.delete(entry.id);
  for (const client of clients) {
    if (client.eventStreamId && client.clientId && entry.deliveredClientIds.has(client.clientId)) {
      deliver(client, { type: 'cancel', eventId: entry.id });
    }
  }
}
function appendSyntheticToolCall(sessionId, callId) {
  const session = sessions.get(sessionId);
  const seq = ++session.cursor;
  const record = { type: 'event', event: {
    seq, time: NOW + seq, type: 'tool/call', surfaceOp: 'append',
    data: {
      turn: 1, step: seq, callId, name: 'bash',
      arguments: JSON.stringify({ description: 'Synthetic QA operation', command: 'echo INTERACTION_QA_ONLY' }),
    },
  } };
  session.records.push(record);
  session.projectionsAsOfSeq = seq;
  for (const stream of streams) {
    if (stream.endpoint === 'session/follow' && stream.sessionId === sessionId) {
      sendFrame(stream.client, itemFrame(stream.id, record));
    }
  }
}
function makeQuestion(kind, callId) {
  const question = (id, fields) => ({ id, question: `${kind}: ${id}?`, ...fields });
  switch (kind) {
    case 'question':
      return [{ id: 'q1', question: 'Which option should the agent use?', detail: 'Choose a synthetic fixture option.', options: [
        { label: 'Option A', description: 'First synthetic choice' }, { label: 'Option B' },
      ] }];
    case 'plan-review':
      return [question('plan', {
        header: 'Plan review', detail: '# Synthetic plan\n\n1. Inspect fixture.\n2. Report findings.',
        options: [{ label: 'Approve plan' }, { label: 'Request changes' }],
        intent: { kind: 'plan-review', approve: 'Approve plan', callId },
      })];
    case 'multiselect':
      return [question('features', { multiSelect: true, options: [
        { label: 'Summary' }, { label: 'Tests' }, { label: 'Screenshots' },
      ] })];
    case 'multiquestion':
      return [
        question('format', { header: 'Output', options: [{ label: 'Markdown' }, { label: 'Plain text' }] }),
        question('include-tests', { options: [{ label: 'Yes' }, { label: 'No' }] }),
      ];
    case 'free-text':
      return [question('destination', { detail: 'Enter a synthetic destination name.' })];
    case 'timed-question':
      return [question('timed', {
        options: [{ label: 'Continue' }, { label: 'Stop' }],
      })];
    default:
      throw new Error(`unknown question kind: ${kind}`);
  }
}
function inject(scenario, sessionId = 'qa-session-a', explicit = undefined) {
  if (!sessions.has(sessionId)) throw new Error('unknown sessionId');
  const id = `qa-event-${String(++eventOrdinal).padStart(4, '0')}`;
  const callId = `qa-call-${String(eventOrdinal).padStart(4, '0')}`;
  let event;
  if (scenario === 'approval') {
    event = { type: 'waterfall', event: 'approval/request', eventId: id, agentId: sessionId,
      request: {
        toolName: 'bash', callId, reason: 'Synthetic command requires approval.',
        displayReason: { en: 'Allow this synthetic operation once', zh: '允许本次合成测试操作' },
      } };
  } else if (scenario === 'subagent-approval') {
    const parentId = sessionId;
    sessionId = `${parentId}-child-agent`;
    if (!sessions.has(sessionId)) {
      const child = makeSession(sessionId, 'QA Child Agent');
      child.origin = 'subagent'; child.parentSessionId = parentId;
      sessions.set(sessionId, child);
      for (const client of clients) if (client.eventStreamId) deliver(client, { type: 'emit', event: 'api-session/added', args: [sessionView(child)] });
    }
    event = { type: 'waterfall', event: 'approval/request', eventId: id, agentId: sessionId,
      request: {
        toolName: 'bash', callId, reason: 'Synthetic child-agent action requires approval.',
        displayReason: { en: 'Allow this synthetic operation once', zh: '允许本次合成测试操作' },
      } };
  } else if (scenario === 'question' || scenario === 'plan-review' || scenario === 'multiselect' || scenario === 'multiquestion' || scenario === 'free-text' || scenario === 'timed-question') {
    const timed = scenario === 'timed-question';
    event = { type: 'waterfall', event: 'user-questions/request', eventId: id, agentId: sessionId,
      request: { questions: makeQuestion(scenario, callId), ...(timed ? { wait: { callId, timed: true } } : {}) } };
  } else if (scenario === 'custom') {
    if (!explicit || explicit.type !== 'waterfall' || typeof explicit.event !== 'string') throw new Error('custom scenario requires a waterfall event object');
    event = { ...explicit, eventId: id };
  } else {
    throw new Error(`unknown scenario: ${scenario}`);
  }
  const entry = { id, sessionId, scenario, event, createdAt: Date.now(), deliveredClientIds: new Set() };
  pending.set(id, entry);
  if (scenario === 'approval' || scenario === 'subagent-approval') appendSyntheticToolCall(sessionId, callId);
  for (const client of clients) if (client.eventStreamId) deliver(client, event);
  return entry;
}
function setContinued(sessionId, callId = 'qa-call-continued', questions = makeQuestion('multiquestion', callId)) {
  const session = sessions.get(sessionId);
  if (!session) throw new Error('unknown sessionId');
  session.values.userQuestions = {
    active: [{ callId, questions, state: 'continued' }],
    settled: [],
  };
  session.projectionsAsOfSeq = session.cursor;
  publishQuestionProjection(session);
  for (const stream of streams) {
    if (stream.endpoint === 'session/follow' && stream.sessionId === sessionId) {
      sendFrame(stream.client, itemFrame(stream.id, snapshot(session)));
    }
  }
  return { sessionId, callId, questions };
}
function outcomeRecord(args, method) {
  const eventId = args.eventId;
  const outcome = args.outcome;
  const existing = pending.get(eventId);
  const client = [...clients].find(item => !item.closed && item.clientId === args.clientId && item.eventStreamId);
  if (!client || !existing || !existing.deliveredClientIds.has(args.clientId)) {
    throw Object.assign(new Error('unknown event or stale Remote event client generation'), { code: 'events/stale-result' });
  }
  const row = { at: new Date().toISOString(), method, clientId: args.clientId, eventId, outcome, matchedPending: true, sessionId: existing.sessionId };
  outcomes.push(row);
  const callId = existing.event.request?.wait?.callId;
  if (callId) endWaitStreams(existing.sessionId, callId);
  cancelWaterfall(existing);
  return row;
}
function rpcValue(method, args) {
  if (['session/page', 'session/create', 'session/projections', 'session/selectModel', 'session/rename', 'session/fork', 'session/prompt', 'session/cancel', 'workspace/create'].includes(method)) args = args.request || {};
  switch (method) {
    case 'session/list':
      return { items: [...sessions.values()].map(sessionView) };
    case 'session/modelCatalog':
      return {
        default: { provider: 'fixture', model: 'mock-model' },
        routableProviders: ['fixture'],
        groups: [{ id: 'fixture', name: 'Synthetic Fixture', models: [{ id: 'mock-model', name: 'Mock Model' }] }],
        failures: [],
      };
    case 'session/page':
      return { records: [], hasMore: false };
    case 'session/projections': {
      const s = sessions.get(args.sessionId);
      if (!s) throw Object.assign(new Error('Unknown fixture session'), { code: 'session/not-found' });
      return { asOfSeq: s.projectionsAsOfSeq, values: s.values };
    }
    case 'llm/listProviders':
    case 'llm/listConfigurableProviders':
      return [];
    case 'settings/describe':
      return { writable: false, namespaces: [] };
    case 'credentials/describe':
      return { credentials: {} };
    case '$events/result':
      outcomeRecord(args, method);
      return null;
    case 'userQuestions/answer': {
      const sessionId = args.agentId || args.sessionId;
      const s = sessions.get(sessionId);
      if (!s) throw Object.assign(new Error('Unknown fixture session'), { code: 'session/not-found' });
      const active = s.values.userQuestions?.active || [];
      const ix = active.findIndex(q => q.callId === args.callId);
      if (ix < 0) return { ok: true, value: false };
      const question = active[ix];
      const answer = args.answer;
      if (!answer || !Array.isArray(answer.answers)) throw Object.assign(new Error('answer must contain answers[]'), { code: 'userQuestions/bad-answer' });
      const ids = answer.answers.map(item => item.id);
      if (new Set(ids).size !== ids.length || ids.length !== question.questions.length || question.questions.some(q => !ids.includes(q.id))) {
        throw Object.assign(new Error('answer IDs must match the question batch'), { code: 'userQuestions/bad-answer' });
      }
      active.splice(ix, 1);
      s.values.userQuestions = { active, settled: [...(s.values.userQuestions?.settled || []), { callId: question.callId, answers: answer.answers }] };
      s.projectionsAsOfSeq = s.cursor;
      publishQuestionProjection(s);
      outcomes.push({ at: new Date().toISOString(), method, sessionId, callId: args.callId, answer, matchedPending: true });
      return { ok: true, value: true };
    }
    default:
      return {};
  }
}
async function readJson(req, limit = 1024 * 1024) {
  let body = '';
  for await (const part of req) {
    body += part;
    if (body.length > limit) throw new Error('request body too large');
  }
  return body ? JSON.parse(body) : {};
}
function parseMuxPayload(frame) {
  const args = frame.payload?.args;
  return args && typeof args === 'object' && !Array.isArray(args) ? args : {};
}
function handleOpen(client, frame) {
  const id = frame.streamId;
  const endpoint = frame.endpoint;
  const args = parseMuxPayload(frame);
  const stream = { client, id, endpoint, sessionId: null };
  streams.add(stream);
  if (endpoint === '$events') {
    client.eventStreamId = id;
    const clientId = `qa-client-${++wsOrdinal}`;
    client.clientId = clientId;
    sendFrame(client, itemFrame(id, { type: 'ready', clientId, host: { home: '/tmp' } }));
    for (const entry of pending.values()) deliver(client, entry.event);
    return;
  }
  if (endpoint === 'session/follow') {
    const request = args.request || {};
    const address = request.address;
    const sessionId = address?.childSessionId || address?.sessionId || args.sessionId || 'qa-session-a';
    stream.sessionId = sessionId;
    const session = sessions.get(sessionId);
    if (!session) return sendFrame(client, { type: 'error', streamId: id, error: { code: 'session/not-found', message: 'Unknown fixture session', details: {} } });
    if (session.origin === 'subagent' && (address?.kind !== 'subagent' || address?.parentSessionId !== session.parentSessionId)) {
      return sendFrame(client, { type: 'error', streamId: id, error: { code: 'session/agent-busy', message: 'Child address and parent are required' } });
    }
    sendFrame(client, itemFrame(id, snapshot(session)));
    return;
  }
  if (endpoint === 'session/control') {
    const projections = {};
    for (const [sessionId, session] of sessions) projections[sessionId] = {
      asOfSeq: session.projectionsAsOfSeq, values: session.values,
    };
    sendFrame(client, itemFrame(id, { type: 'baseline', value: { projections } }));
    return;
  }
  if (endpoint === 'workspace/follow') {
    sendFrame(client, itemFrame(id, { value: { items: [] } }));
    return;
  }
  if (endpoint === 'userQuestions/attachWait') {
    const callId = args.callId;
    const sessionId = args.agentId || args.sessionId || 'qa-session-a';
    sendFrame(client, itemFrame(id, { remainingMs: 60_000, sessionId, callId }));
    stream.sessionId = sessionId;
    stream.callId = callId;
    return;
  }
  sendFrame(client, { type: 'error', streamId: id, error: { code: 'fixture/endpoint-unsupported', message: `Unsupported fixture stream: ${endpoint}`, details: {} } });
}
function handleMux(client, frame) {
  if (!frame || typeof frame !== 'object') return;
  if (frame.type === 'open' && typeof frame.streamId === 'string' && typeof frame.endpoint === 'string') return handleOpen(client, frame);
  if (frame.type === 'cancel' && typeof frame.streamId === 'string') {
    for (const stream of [...streams]) if (stream.client === client && stream.id === frame.streamId) streams.delete(stream);
    if (client.eventStreamId === frame.streamId) client.eventStreamId = null;
  }
}
function dispatchRpc(req, res) {
  readJson(req).then(body => {
    if (body.type !== 'client-request' || typeof body.rpcId !== 'string' || typeof body.method !== 'string') throw new Error('invalid client-request');
    const args = body.payload?.args;
    if (!args || typeof args !== 'object' || Array.isArray(args)) throw new Error('payload.args must be an object');
    try {
      const value = rpcValue(body.method, args);
      json(res, 200, response(body.rpcId, value));
    } catch (error) {
      json(res, 200, response(body.rpcId, null, false, { code: error.code || 'fixture/rpc-error', message: error.message }));
    }
  }).catch(error => json(res, 400, { error: { code: 'fixture/invalid-request', message: error.message } }));
}
function admin(req, res, path) {
  if (!assertLoopback(req)) return json(res, 403, { error: 'loopback only' });
  readJson(req).then(body => {
    if (path === '/__qa/state' && req.method === 'GET') return json(res, 200, {
      sessions: [...sessions.values()].map(s => ({ sessionId: s.sessionId, title: s.title, userQuestions: s.values.userQuestions })),
      pending: [...pending.values()].map(p => ({ id: p.id, sessionId: p.sessionId, scenario: p.scenario, event: p.event })),
      outcomes,
      openStreams: [...streams].map(s => ({ endpoint: s.endpoint, sessionId: s.sessionId, callId: s.callId })),
    });
    if (path === '/__qa/inject' && req.method === 'POST') {
      const scenarios = body.scenario === 'two-sessions-multiple-pending'
        ? [['approval', 'qa-session-a'], ['question', 'qa-session-a'], ['approval', 'qa-session-b'], ['multiquestion', 'qa-session-b']]
        : [[body.scenario || 'approval', body.sessionId || 'qa-session-a']];
      const added = scenarios.map(([scenario, sessionId]) => inject(scenario, sessionId, body.event));
      return json(res, 200, { injected: added });
    }
    if (path === '/__qa/continued' && req.method === 'POST') {
      return json(res, 200, { continued: setContinued(body.sessionId || 'qa-session-a', body.callId) });
    }
    if (path === '/__qa/timeout' && req.method === 'POST') {
      const entry = body.eventId
        ? pending.get(body.eventId)
        : [...pending.values()].find(item => item.event.request?.wait?.callId === body.callId);
      if (!entry || entry.scenario !== 'timed-question') return json(res, 404, { error: 'timed question pending event not found' });
      const callId = entry.event.request.wait.callId;
      const questions = entry.event.request.questions;
      const continued = setContinued(entry.sessionId, callId, questions);
      endWaitStreams(entry.sessionId, callId);
      cancelWaterfall(entry);
      outcomes.push({ at: new Date().toISOString(), method: '__qa/timeout', sessionId: entry.sessionId, callId, eventId: entry.id, matchedPending: true });
      return json(res, 200, { timedOut: { eventId: entry.id, ...continued } });
    }
    if (path === '/__qa/cancel' && req.method === 'POST') {
      const entry = pending.get(body.eventId);
      if (!entry) return json(res, 404, { error: 'pending event not found' });
      const callId = entry.event.request?.wait?.callId;
      if (callId) endWaitStreams(entry.sessionId, callId);
      cancelWaterfall(entry);
      return json(res, 200, { cancelled: body.eventId });
    }
    if (path === '/__qa/reset' && req.method === 'POST') {
      pending.clear(); outcomes.length = 0; eventOrdinal = 0;
      for (const session of sessions.values()) session.values.userQuestions = { active: [], settled: [] };
      return json(res, 200, { reset: true });
    }
    return json(res, 404, { error: 'unknown admin route' });
  }).catch(error => json(res, 400, { error: error.message }));
}
function makeWsFrame(text, opcode = 1) {
  const payload = Buffer.from(text);
  let header;
  if (payload.length < 126) {
    header = Buffer.from([0x80 | opcode, payload.length]);
  } else if (payload.length < 65536) {
    header = Buffer.alloc(4); header[0] = 0x80 | opcode; header[1] = 126; header.writeUInt16BE(payload.length, 2);
  } else {
    header = Buffer.alloc(10); header[0] = 0x80 | opcode; header[1] = 127; header.writeBigUInt64BE(BigInt(payload.length), 2);
  }
  return Buffer.concat([header, payload]);
}
function upgrade(req, socket, head) {
  if (req.url !== WS_PATH || !assertLoopback(req)) { socket.destroy(); return; }
  const key = req.headers['sec-websocket-key'];
  if (typeof key !== 'string') { socket.destroy(); return; }
  const accept = crypto.createHash('sha1').update(key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64');
  socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ' + accept + '\r\n\r\n');
  const client = {
    socket, buffer: Buffer.alloc(0), closed: false, eventStreamId: null, clientId: null,
    writeText(text) { if (!this.closed && !socket.destroyed) socket.write(makeWsFrame(text)); },
    close() { if (this.closed) return; this.closed = true; try { socket.end(makeWsFrame('', 8)); } catch {} },
  };
  clients.add(client);
  socket.on('data', chunk => {
    client.buffer = Buffer.concat([client.buffer, chunk]);
    while (true) {
      if (client.buffer.length < 2) return;
      const first = client.buffer[0]; const second = client.buffer[1];
      const opcode = first & 0x0f; const masked = Boolean(second & 0x80);
      let length = second & 0x7f; let offset = 2;
      if (length === 126) { if (client.buffer.length < 4) return; length = client.buffer.readUInt16BE(2); offset = 4; }
      else if (length === 127) { if (client.buffer.length < 10) return; const big = client.buffer.readBigUInt64BE(2); if (big > 16n * 1024n * 1024n) return client.close(); length = Number(big); offset = 10; }
      const maskBytes = masked ? 4 : 0;
      if (client.buffer.length < offset + maskBytes + length) return;
      const mask = masked ? client.buffer.subarray(offset, offset + 4) : null;
      offset += maskBytes;
      const payload = Buffer.from(client.buffer.subarray(offset, offset + length));
      client.buffer = client.buffer.subarray(offset + length);
      if (mask) for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
      if (opcode === 8) { client.close(); return; }
      if (opcode === 9) { socket.write(makeWsFrame(payload.toString(), 10)); continue; }
      if (opcode !== 1) continue;
      try { handleMux(client, JSON.parse(payload.toString('utf8'))); }
      catch (error) { console.error('Rejected malformed mux frame:', error.message); client.close(); return; }
    }
  });
  socket.on('close', () => {
    client.closed = true; clients.delete(client);
    for (const stream of [...streams]) if (stream.client === client) streams.delete(stream);
  });
  socket.on('error', () => {});
  if (head?.length) socket.emit('data', head);
}
const server = http.createServer((req, res) => {
  const url = new URL(req.url || '/', `http://${HOST}:${PORT}`);
  if (!assertLoopback(req)) return json(res, 403, { error: 'loopback only' });
  if (url.pathname.startsWith(ADMIN_PREFIX)) return admin(req, res, url.pathname);
  if (req.method === 'POST' && url.pathname.startsWith('/api/')) return dispatchRpc(req, res);
  if (req.method === 'GET' && url.pathname === '/') return json(res, 200, { service: 'synthetic-dsh-rc2-interaction-fixture', loopback: true });
  json(res, 404, { error: 'not found' });
});
server.on('upgrade', upgrade);
server.listen(PORT, HOST, () => console.log(`Synthetic rc.2 fixture ready at http://${HOST}:${PORT} (loopback only; no auth, no tool execution)`));
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => {
  for (const client of clients) client.close();
  server.close(() => process.exit(0));
});
