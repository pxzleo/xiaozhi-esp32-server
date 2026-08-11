export const PROACTIVE_MODES = ['conservative', 'active', 'aggressive'];
export const PROACTIVE_TOPICS = ['reminder', 'calendar', 'weather', 'news', 'music', 'health', 'habit', 'system'];
export const PROACTIVE_EVENT_TYPES = [
  'reminder',
  'due_soon',
  'schedule_change',
  'weather_alert',
  'news_alert',
  'mobile_alert',
  'music_status',
  'habit_suggestion',
  'system',
];
export const PROACTIVE_DELIVERY_STATUSES = ['pending', 'claimed', 'delivered', 'failed', 'expired', 'dismissed'];
export const MOBILE_EVENT_TYPES = ['notification.state_changed', 'location.transition'];
export const MOBILE_PROCESSING_STATUSES = ['received', 'prefiltered', 'classified', 'ignored', 'converted', 'error'];
export const MONITOR_PRESETS = Object.freeze({
  timely: { weather: 10, news: 5 },
  balanced: { weather: 30, news: 10 },
  economical: { weather: 60, news: 30 },
});
export const WEATHER_HAZARD_TYPES = [
  'rainstorm', 'thunderstorm', 'hail', 'blizzard', 'high_wind',
  'high_temperature', 'low_temperature', 'temperature_drop',
];
export const OFFICIAL_WARNING_LEVELS = ['minor', 'moderate', 'severe', 'extreme'];
export const NEWS_CATEGORIES = [
  'public_safety', 'natural_disaster', 'major_policy', 'international_conflict',
  'major_economy', 'major_technology',
];

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
  return { conservative: 1, active: 5, aggressive: 0 }[mode] ?? 1;
}

export function belongsToDevice(value, deviceId) {
  return Boolean(value && deviceId && value.device_id === deviceId);
}

export function listBelongsToDevice(values, deviceId) {
  return Array.isArray(values) && values.every(value => belongsToDevice(value, deviceId));
}

export function mobileAuditBelongsToContext(values, deviceId, instanceId) {
  return Array.isArray(values) && values.every(value =>
    value && value.device_id === deviceId && value.mobile_instance_id === instanceId);
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

const DEFAULT_WEATHER_CONFIG = Object.freeze({
  source: 'agent_plugin',
  hazard_types: [],
  minimum_warning_severity: 'moderate',
  precip_probability: 70,
  wind_speed_kmh: 62,
  high_temp_c: 35,
  low_temp_c: 0,
  temp_drop_24h_c: 8,
  forecast_hours: 6,
  cooldown_minutes: 720,
});

const DEFAULT_NEWS_CONFIG = Object.freeze({
  source_mode: 'agent_plugin',
  sources: [],
  categories: [],
  confidence: 0.85,
  cooldown_minutes: 120,
  dedupe_hours: 24,
  scope: 'domestic_and_international',
});

function monitorSetting(value, type) {
  const defaults = type === 'weather'
    ? { enabled: true, interval_minutes: 30, config: DEFAULT_WEATHER_CONFIG }
    : { enabled: true, interval_minutes: 10, config: DEFAULT_NEWS_CONFIG };
  const config = value && value.config && typeof value.config === 'object' ? value.config : {};
  const normalizedConfig = { ...defaults.config, ...config };
  if (type === 'weather') {
    normalizedConfig.hazard_types = Array.isArray(config.hazard_types)
      ? [...config.hazard_types]
      : [...defaults.config.hazard_types];
  } else {
    normalizedConfig.sources = Array.isArray(config.sources) ? [...config.sources] : [...defaults.config.sources];
    normalizedConfig.categories = Array.isArray(config.categories)
      ? [...config.categories]
      : [...defaults.config.categories];
  }
  return {
    enabled: typeof value?.enabled === 'boolean' ? value.enabled : defaults.enabled,
    interval_minutes: Number.isInteger(value?.interval_minutes)
      ? value.interval_minutes
      : defaults.interval_minutes,
    config: normalizedConfig,
  };
}

export function createMonitorsForm(monitors = {}) {
  return {
    weather: monitorSetting(monitors.weather, 'weather'),
    news: monitorSetting(monitors.news, 'news'),
  };
}

export function monitorPreset(form) {
  const match = Object.entries(MONITOR_PRESETS).find(([, value]) =>
    form.weather.interval_minutes === value.weather && form.news.interval_minutes === value.news);
  return match ? match[0] : 'custom';
}

export function applyMonitorPreset(form, preset) {
  const intervals = MONITOR_PRESETS[preset];
  if (!intervals) return form;
  return {
    ...form,
    weather: { ...form.weather, interval_minutes: intervals.weather },
    news: { ...form.news, interval_minutes: intervals.news },
  };
}

export function inheritedWeatherLocation(monitors) {
  const location = monitors?.weather_location;
  return typeof location === 'string' && location.trim() ? location.trim() : '';
}

export function inheritedWeatherLocationError(monitors) {
  const error = monitors?.weather_location_error;
  return typeof error === 'string' && error.trim() ? error.trim() : '';
}

export function monitorClassifierStatus(monitors) {
  const classifier = monitors?.classifier;
  if (!classifier || typeof classifier.configured !== 'boolean' || typeof classifier.available !== 'boolean') {
    return 'unknown';
  }
  if (classifier.available && !classifier.configured) return 'unknown';
  return classifier.available ? 'available' : 'unavailable';
}

export function monitorGlobalStatus(monitors) {
  if (typeof monitors?.external_monitoring_enabled !== 'boolean') return 'unknown';
  return monitors.external_monitoring_enabled ? 'enabled' : 'disabled';
}

export function externalMonitoringSetting(value) {
  return value && typeof value.enabled === 'boolean' ? value.enabled : null;
}

export function recoverExternalMonitoringFailure(current) {
  return current;
}

export function externalMonitoringEditable(loaded, saving) {
  return loaded === true && saving === false;
}

function validInteger(value, minimum, maximum) {
  return Number.isInteger(value) && value >= minimum && value <= maximum;
}

function validStringList(value, maximumItems, maximumLength) {
  return Array.isArray(value) && value.length <= maximumItems && value.every(item =>
    typeof item === 'string' && item.trim().length > 0 && item.length <= maximumLength);
}

export function validateMonitors(form) {
  if (!form || typeof form.weather?.enabled !== 'boolean' || typeof form.news?.enabled !== 'boolean') {
    return 'enabled';
  }
  if (!validInteger(form.weather.interval_minutes, 5, 1440) ||
      !validInteger(form.news.interval_minutes, 5, 1440)) return 'interval_minutes';
  const weather = form.weather.config || {};
  if (weather.source !== 'agent_plugin' ||
      !validStringList(weather.hazard_types, 16, 32) ||
      weather.hazard_types.some(type => !WEATHER_HAZARD_TYPES.includes(type)) ||
      !OFFICIAL_WARNING_LEVELS.includes(weather.minimum_warning_severity)) return 'weather_config';
  const weatherRanges = [
    ['precip_probability', 0, 100], ['wind_speed_kmh', 0, 300],
    ['high_temp_c', -50, 60], ['low_temp_c', -50, 60],
    ['temp_drop_24h_c', 0, 60], ['forecast_hours', 1, 168],
    ['cooldown_minutes', 1, 10080],
  ];
  if (weatherRanges.some(([key, min, max]) => !validInteger(weather[key], min, max)) ||
      weather.low_temp_c >= weather.high_temp_c) return 'weather_thresholds';
  const news = form.news.config || {};
  if (news.source_mode !== 'agent_plugin' ||
      !validStringList(news.sources, 16, 200) ||
      !validStringList(news.categories, 16, 64) ||
      news.categories.some(category => !NEWS_CATEGORIES.includes(category)) ||
      news.scope !== 'domestic_and_international') return 'news_config';
  if (typeof news.confidence !== 'number' || !Number.isFinite(news.confidence) ||
      news.confidence < 0.5 || news.confidence > 1 ||
      !validInteger(news.cooldown_minutes, 1, 10080) ||
      !validInteger(news.dedupe_hours, 1, 720)) return 'news_thresholds';
  return '';
}

export function monitorsPayload(form) {
  return {
    weather: {
      enabled: form.weather.enabled,
      interval_minutes: form.weather.interval_minutes,
      config: {
        source: form.weather.config.source,
        hazard_types: [...form.weather.config.hazard_types],
        minimum_warning_severity: form.weather.config.minimum_warning_severity,
        precip_probability: form.weather.config.precip_probability,
        wind_speed_kmh: form.weather.config.wind_speed_kmh,
        high_temp_c: form.weather.config.high_temp_c,
        low_temp_c: form.weather.config.low_temp_c,
        temp_drop_24h_c: form.weather.config.temp_drop_24h_c,
        forecast_hours: form.weather.config.forecast_hours,
        cooldown_minutes: form.weather.config.cooldown_minutes,
      },
    },
    news: {
      enabled: form.news.enabled,
      interval_minutes: form.news.interval_minutes,
      config: {
        source_mode: form.news.config.source_mode,
        sources: [...form.news.config.sources],
        categories: [...form.news.config.categories],
        confidence: form.news.config.confidence,
        cooldown_minutes: form.news.config.cooldown_minutes,
        dedupe_hours: form.news.config.dedupe_hours,
        scope: form.news.config.scope,
      },
    },
  };
}

export function recoverMonitorsFailure(current, clearState) {
  if (clearState) return { monitors: {}, loadedDeviceId: '', form: createMonitorsForm() };
  return current;
}

export function classifierModelId(value) {
  return typeof value?.model_id === 'string' ? value.model_id.trim() : '';
}

export function validClassifierModelId(modelId) {
  return typeof modelId === 'string' && modelId.trim().length > 0 && modelId.trim().length <= 64;
}

export function normalizeTime(value) {
  return typeof value === 'string' && /^\d{2}:\d{2}/.test(value) ? value.slice(0, 5) : '';
}

export function createPreferenceForm(preference = {}) {
  const effectiveMode = preference.mode === 'today_silent' ? preference.previous_mode : preference.mode;
  const effectiveLimit = preference.mode === 'today_silent'
    ? preference.previous_daily_limit
    : preference.daily_limit;
  const mode = PROACTIVE_MODES.includes(effectiveMode) ? effectiveMode : 'aggressive';
  return {
    mode,
    daily_limit: mode === 'aggressive'
      ? 0
      : Number.isInteger(effectiveLimit)
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
  const maximum = { conservative: 1, active: 5, aggressive: 0 }[form.mode];
  const validLimit = form.mode === 'aggressive'
    ? form.daily_limit === 0
    : Number.isInteger(form.daily_limit) && form.daily_limit >= 1 && form.daily_limit <= maximum;
  if (!validLimit) {
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
    daily_limit: form.mode === 'aggressive' ? 0 : form.daily_limit,
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

export function buildMobileEventQuery(filters) {
  const params = new URLSearchParams({
    mobile_instance_id: filters.mobile_instance_id,
    page: String(filters.page),
    limit: String(filters.limit),
  });
  ['type', 'processing_status', 'delivery_status', 'from', 'to'].forEach(key => {
    if (filters[key]) params.set(key, filters[key]);
  });
  return params.toString();
}
