export const PROACTIVE_MODES = ['conservative', 'active', 'aggressive'];
export const PROACTIVE_TOPICS = ['reminder', 'calendar', 'weather', 'music', 'health', 'habit', 'system'];
export const PROACTIVE_EVENT_TYPES = [
  'reminder',
  'due_soon',
  'schedule_change',
  'weather_alert',
  'music_status',
  'habit_suggestion',
  'system',
];
export const PROACTIVE_DELIVERY_STATUSES = ['pending', 'claimed', 'delivered', 'failed', 'expired', 'dismissed'];

export class DeviceRequestGate {
  constructor() {
    this.generation = 0;
    this.deviceId = '';
    this.channelGenerations = new Map();
  }

  activate(deviceId) {
    this.generation += 1;
    this.deviceId = deviceId || '';
    this.channelGenerations.clear();
    return this.snapshot();
  }

  begin(channel = 'default') {
    const channelGeneration = (this.channelGenerations.get(channel) || 0) + 1;
    this.channelGenerations.set(channel, channelGeneration);
    return this.snapshot(channel, channelGeneration);
  }

  snapshot(channel = 'default', channelGeneration = this.channelGenerations.get(channel) || 0) {
    return { generation: this.generation, deviceId: this.deviceId, channel, channelGeneration };
  }

  isCurrent(request) {
    return Boolean(request && request.deviceId) &&
      request.generation === this.generation &&
      request.deviceId === this.deviceId &&
      request.channelGeneration === (this.channelGenerations.get(request.channel) || 0);
  }

  invalidate() {
    this.generation += 1;
    this.deviceId = '';
    this.channelGenerations.clear();
  }
}

export function defaultDailyLimit(mode) {
  return { conservative: 1, active: 3, aggressive: 5 }[mode] || 1;
}

export function belongsToDevice(value, deviceId) {
  return Boolean(value && deviceId && value.device_id === deviceId);
}

export function listBelongsToDevice(values, deviceId) {
  return Array.isArray(values) && values.every(value => belongsToDevice(value, deviceId));
}

export function recoverPreferenceFailure(current, clearState) {
  if (clearState) {
    return { preference: {}, loadedDeviceId: '', form: createPreferenceForm() };
  }
  return {
    preference: current.preference,
    loadedDeviceId: current.loadedDeviceId,
    form: current.form,
  };
}

export function normalizeTime(value) {
  return typeof value === 'string' && /^\d{2}:\d{2}/.test(value) ? value.slice(0, 5) : '';
}

export function createPreferenceForm(preference = {}) {
  const effectiveMode = preference.mode === 'today_silent' ? preference.previous_mode : preference.mode;
  const effectiveLimit = preference.mode === 'today_silent'
    ? preference.previous_daily_limit
    : preference.daily_limit;
  const mode = PROACTIVE_MODES.includes(effectiveMode) ? effectiveMode : 'conservative';
  return {
    mode,
    daily_limit: Number.isInteger(effectiveLimit)
      ? effectiveLimit
      : defaultDailyLimit(mode),
    quiet_start: normalizeTime(preference.quiet_start),
    quiet_end: normalizeTime(preference.quiet_end),
    allowed_topics: Array.isArray(preference.allowed_topics) ? [...preference.allowed_topics] : [],
    blocked_topics: Array.isArray(preference.blocked_topics) ? [...preference.blocked_topics] : [],
  };
}

export function validatePreference(form) {
  if (!PROACTIVE_MODES.includes(form.mode)) return 'mode';
  const maximum = { conservative: 1, active: 3, aggressive: 5 }[form.mode];
  if (!Number.isInteger(form.daily_limit) || form.daily_limit < 1 || form.daily_limit > maximum) {
    return 'daily_limit';
  }
  if (Boolean(form.quiet_start) !== Boolean(form.quiet_end) ||
      (form.quiet_start && form.quiet_start === form.quiet_end)) {
    return 'quiet_window';
  }
  if (form.allowed_topics.some(topic => form.blocked_topics.includes(topic))) return 'topics';
  return '';
}

export function preferencePayload(form) {
  return {
    mode: form.mode,
    daily_limit: form.daily_limit,
    quiet_start: form.quiet_start || null,
    quiet_end: form.quiet_end || null,
    allowed_topics: [...form.allowed_topics],
    blocked_topics: [...form.blocked_topics],
  };
}

export function buildEventQuery(filters) {
  const params = new URLSearchParams({
    device_id: filters.device_id,
    page: String(filters.page),
    limit: String(filters.limit),
  });
  ['topic', 'delivery_status', 'event_type'].forEach(key => {
    if (filters[key]) params.set(key, filters[key]);
  });
  return params.toString();
}
