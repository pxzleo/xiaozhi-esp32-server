import assert from 'node:assert/strict';
import test from 'node:test';

import {
  buildEventQuery,
  createPreferenceForm,
  preferencePayload,
  validatePreference,
} from '../src/utils/proactiveAssistant.mjs';

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
