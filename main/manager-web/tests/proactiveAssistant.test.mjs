import assert from 'node:assert/strict';
import test from 'node:test';

import {
  DeviceRequestGate,
  belongsToDevice,
  buildEventQuery,
  createPreferenceForm,
  listBelongsToDevice,
  preferencePayload,
  recoverPreferenceFailure,
  validatePreference,
} from '../src/utils/proactiveAssistant.mjs';

test('clears failed loads but preserves a verified preference after mutation failure', () => {
  const current = {
    preference: { device_id: 'device-a', mode: 'active' },
    loadedDeviceId: 'device-a',
    form: createPreferenceForm({ mode: 'active', daily_limit: 3 }),
  };

  assert.deepEqual(recoverPreferenceFailure(current, true), {
    preference: {},
    loadedDeviceId: '',
    form: createPreferenceForm(),
  });
  assert.deepEqual(recoverPreferenceFailure(current, false), current);
});

test('rejects responses from an older generation or another device', () => {
  const gate = new DeviceRequestGate();
  gate.activate('device-a');
  const firstDeviceRequest = gate.begin('preference');
  assert.equal(gate.isCurrent(firstDeviceRequest), true);

  gate.activate('device-b');
  const secondDeviceRequest = gate.begin('preference');
  assert.equal(gate.isCurrent(firstDeviceRequest), false);
  assert.equal(gate.isCurrent(secondDeviceRequest), true);

  gate.invalidate();
  assert.equal(gate.isCurrent(secondDeviceRequest), false);
});

test('rejects an older response in the same request channel', () => {
  const gate = new DeviceRequestGate();
  gate.activate('device-a');
  const olderEvents = gate.begin('events');
  const newerEvents = gate.begin('events');

  assert.equal(gate.isCurrent(olderEvents), false);
  assert.equal(gate.isCurrent(newerEvents), true);
});

test('requires response records to belong to the requested device', () => {
  assert.equal(belongsToDevice({ device_id: 'device-a' }, 'device-a'), true);
  assert.equal(belongsToDevice({ device_id: 'device-b' }, 'device-a'), false);
  assert.equal(listBelongsToDevice([], 'device-a'), true);
  assert.equal(listBelongsToDevice([{ device_id: 'device-a' }], 'device-a'), true);
  assert.equal(listBelongsToDevice([{ device_id: 'device-b' }], 'device-a'), false);
});

test('normalizes server preference without exposing today_silent as a permanent mode', () => {
  const form = createPreferenceForm({
    mode: 'today_silent',
    previous_mode: 'active',
    daily_limit: 0,
    quiet_start: '22:30:00',
    quiet_end: '07:00:00',
    allowed_topics: ['calendar'],
  });

  assert.equal(form.mode, 'active');
  assert.equal(form.daily_limit, 3);
  assert.equal(form.quiet_start, '22:30');
  assert.deepEqual(form.allowed_topics, ['calendar']);
});

test('validates limits, complete quiet windows and disjoint topics', () => {
  const form = createPreferenceForm({ mode: 'active', daily_limit: 3 });
  assert.equal(validatePreference(form), '');
  assert.equal(validatePreference({ ...form, daily_limit: 4 }), 'daily_limit');
  assert.equal(validatePreference({ ...form, quiet_start: '22:00' }), 'quiet_window');
  assert.equal(validatePreference({ ...form, allowed_topics: ['music'], blocked_topics: ['music'] }), 'topics');
});

test('builds an exact preference payload and omits empty event filters', () => {
  const form = createPreferenceForm({ mode: 'conservative', daily_limit: 1 });
  assert.deepEqual(preferencePayload(form), {
    mode: 'conservative',
    daily_limit: 1,
    quiet_start: null,
    quiet_end: null,
    allowed_topics: [],
    blocked_topics: [],
  });

  const query = buildEventQuery({
    device_id: 'device id', page: 2, limit: 20, topic: 'weather', delivery_status: '', event_type: '',
  });
  assert.equal(query, 'device_id=device+id&page=2&limit=20&topic=weather');
});
