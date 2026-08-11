import assert from 'node:assert/strict';
import test from 'node:test';

import {
  PROACTIVE_DELIVERY_STATUSES,
  PROACTIVE_EVENT_TYPES,
  PROACTIVE_TOPICS,
  DeviceRequestGate,
  applyMonitorPreset,
  belongsToDevice,
  buildEventQuery,
  buildMobileEventQuery,
  classifierModelId,
  createMonitorsForm,
  createPreferenceForm,
  inheritedWeatherLocation,
  inheritedWeatherLocationError,
  listBelongsToDevice,
  mobileAuditBelongsToContext,
  monitorPreset,
  monitorClassifierStatus,
  monitorGlobalStatus,
  monitorsPayload,
  preferencePayload,
  recoverMonitorsFailure,
  recoverExternalMonitoringFailure,
  recoverPreferenceFailure,
  validateMonitors,
  validatePreference,
  validClassifierModelId,
  externalMonitoringSetting,
  externalMonitoringEditable,
} from '../src/utils/proactiveAssistant.mjs';

test('exposes claimed events in the delivery status filter', () => {
  assert.equal(PROACTIVE_DELIVERY_STATUSES.includes('claimed'), true);
  assert.equal(PROACTIVE_TOPICS.includes('news'), true);
  assert.equal(PROACTIVE_TOPICS.length, 8);
  assert.equal(PROACTIVE_EVENT_TYPES.includes('news_alert'), true);
  assert.equal(PROACTIVE_EVENT_TYPES.includes('mobile_alert'), true);
});

test('creates manager-api monitor defaults and exact payloads', () => {
  const form = createMonitorsForm();
  assert.equal(form.weather.enabled, true);
  assert.equal(form.weather.interval_minutes, 30);
  assert.equal(form.weather.config.precip_probability, 70);
  assert.equal(form.news.enabled, true);
  assert.equal(form.news.interval_minutes, 10);
  assert.equal(form.news.config.confidence, 0.85);
  assert.equal(validateMonitors(form), '');
  assert.deepEqual(monitorsPayload(form), {
    weather: {
      enabled: true,
      interval_minutes: 30,
      config: {
        source: 'agent_plugin', hazard_types: [], minimum_warning_severity: 'moderate',
        precip_probability: 70, wind_speed_kmh: 62, high_temp_c: 35, low_temp_c: 0,
        temp_drop_24h_c: 8, forecast_hours: 6, cooldown_minutes: 720,
      },
    },
    news: {
      enabled: true,
      interval_minutes: 10,
      config: {
        source_mode: 'agent_plugin', sources: [], categories: [], confidence: 0.85,
        cooldown_minutes: 120, dedupe_hours: 24, scope: 'domestic_and_international',
      },
    },
  });
});

test('strictly validates monitor boundaries and config shapes', () => {
  const form = createMonitorsForm();
  assert.equal(validateMonitors({ ...form, weather: { ...form.weather, interval_minutes: 4 } }), 'interval_minutes');
  assert.equal(validateMonitors({ ...form, news: { ...form.news, interval_minutes: 10.5 } }), 'interval_minutes');
  assert.equal(validateMonitors({
    ...form,
    weather: { ...form.weather, config: { ...form.weather.config, precip_probability: 101 } },
  }), 'weather_thresholds');
  assert.equal(validateMonitors({
    ...form,
    weather: { ...form.weather, config: { ...form.weather.config, low_temp_c: 35 } },
  }), 'weather_thresholds');
  assert.equal(validateMonitors({
    ...form,
    news: { ...form.news, config: { ...form.news.config, confidence: 0.49 } },
  }), 'news_thresholds');
  assert.equal(validateMonitors({
    ...form,
    news: { ...form.news, config: { ...form.news.config, sources: [''] } },
  }), 'news_config');
  assert.equal(validateMonitors({
    ...form,
    weather: { ...form.weather, config: { ...form.weather.config, hazard_types: ['arbitrary'] } },
  }), 'weather_config');
  assert.equal(validateMonitors({
    ...form,
    weather: { ...form.weather, config: { ...form.weather.config, minimum_warning_severity: 'warning' } },
  }), 'weather_config');
  assert.equal(validateMonitors({
    ...form,
    news: { ...form.news, config: { ...form.news.config, categories: ['arbitrary'] } },
  }), 'news_config');
});

test('applies interval presets and marks custom intervals', () => {
  const balanced = createMonitorsForm();
  assert.equal(monitorPreset(balanced), 'balanced');
  const timely = applyMonitorPreset(balanced, 'timely');
  assert.equal(timely.weather.interval_minutes, 10);
  assert.equal(timely.news.interval_minutes, 5);
  assert.equal(monitorPreset(timely), 'timely');
  assert.equal(monitorPreset({ ...timely, news: { ...timely.news, interval_minutes: 7 } }), 'custom');
});

test('derives only an authoritative inherited location and preserves failed edits', () => {
  const monitors = { weather_location: ' 广州 ', weather_location_error: null };
  assert.equal(inheritedWeatherLocation(monitors), '广州');
  assert.equal(inheritedWeatherLocationError(monitors), '');
  assert.equal(inheritedWeatherLocationError({ weather_location_error: 'default_location_missing' }),
    'default_location_missing');
  assert.equal(inheritedWeatherLocation({ weather: { state: { baseline: { location_id: '101280101' } } } }), '');
  assert.equal(inheritedWeatherLocation({}), '');
  const current = { monitors, loadedDeviceId: 'device-a', form: createMonitorsForm() };
  assert.equal(recoverMonitorsFailure(current, false), current);
  assert.deepEqual(recoverMonitorsFailure(current, true), {
    monitors: {}, loadedDeviceId: '', form: createMonitorsForm(),
  });
});

test('uses the owner-safe classifier availability embedded in the monitor response', () => {
  assert.equal(monitorClassifierStatus({ classifier: { configured: true, available: true, error: null } }),
    'available');
  assert.equal(monitorClassifierStatus({
    classifier: { configured: false, available: false, error: 'not_configured' },
  }), 'unavailable');
  assert.equal(monitorClassifierStatus({ classifier: { configured: false, available: true, error: null } }),
    'unknown');
  assert.equal(monitorClassifierStatus({}), 'unknown');
});

test('strictly reads the global monitoring gate without granting admin access to device views', () => {
  assert.equal(monitorGlobalStatus({ external_monitoring_enabled: true }), 'enabled');
  assert.equal(monitorGlobalStatus({ external_monitoring_enabled: false }), 'disabled');
  assert.equal(monitorGlobalStatus({}), 'unknown');
  assert.equal(externalMonitoringSetting({ enabled: true }), true);
  assert.equal(externalMonitoringSetting({ enabled: false }), false);
  assert.equal(externalMonitoringSetting({ enabled: 'false' }), null);
});

test('preserves the administrator global switch after a save failure', () => {
  const current = { enabled: true, loaded: true };
  assert.equal(recoverExternalMonitoringFailure(current), current);
  assert.equal(externalMonitoringEditable(true, false), true);
  assert.equal(externalMonitoringEditable(true, true), false);
  assert.equal(externalMonitoringEditable(false, false), false);
});

test('normalizes and strictly validates the dedicated classifier model id', () => {
  assert.equal(classifierModelId({ model_id: ' LLM_model ' }), 'LLM_model');
  assert.equal(classifierModelId({}), '');
  assert.equal(validClassifierModelId('LLM_model'), true);
  assert.equal(validClassifierModelId('   '), false);
  assert.equal(validClassifierModelId('x'.repeat(65)), false);
});

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
    previous_daily_limit: 3,
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
  const form = createPreferenceForm({ mode: 'active', daily_limit: 5 });
  assert.equal(validatePreference(form), '');
  assert.equal(validatePreference({ ...form, daily_limit: 6 }), 'daily_limit');
  assert.equal(validatePreference({ ...form, quiet_start: '22:00' }), 'quiet_window');
  assert.equal(validatePreference({ ...form, allowed_topics: ['music'], blocked_topics: ['music'] }), 'topics');
});

test('aggressive is always unlimited and active defaults to five', () => {
  assert.equal(createPreferenceForm({ mode: 'aggressive', daily_limit: 4 }).daily_limit, 0);
  assert.equal(createPreferenceForm({ mode: 'active' }).daily_limit, 5);
  const aggressive = createPreferenceForm({ mode: 'aggressive' });
  assert.equal(validatePreference(aggressive), '');
  assert.equal(validatePreference({ ...aggressive, daily_limit: 1 }), 'daily_limit');
  assert.equal(preferencePayload(aggressive).daily_limit, 0);
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

test('builds strict mobile audit filters and rejects cross-device response rows', () => {
  const query = buildMobileEventQuery({
    mobile_instance_id: 'mob_0123456789abcdef0123456789abcdef',
    page: 2,
    limit: 20,
    type: 'notification.state_changed',
    processing_status: 'converted',
    delivery_status: 'delivered',
    from: '2026-08-10T00:00:00Z',
    to: '',
  });
  assert.equal(query, 'mobile_instance_id=mob_0123456789abcdef0123456789abcdef&page=2&limit=20&type=notification.state_changed&processing_status=converted&delivery_status=delivered&from=2026-08-10T00%3A00%3A00Z');
  assert.equal(mobileAuditBelongsToContext([], 'device-a', 'mob-a'), true);
  assert.equal(mobileAuditBelongsToContext([
    { device_id: 'device-a', mobile_instance_id: 'mob-a' },
  ], 'device-a', 'mob-a'), true);
  assert.equal(mobileAuditBelongsToContext([
    { device_id: 'device-b', mobile_instance_id: 'mob-a' },
  ], 'device-a', 'mob-a'), false);
});
