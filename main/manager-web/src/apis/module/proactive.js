import { getServiceUrl } from '../api';
import RequestService from '../httpRequest';
import { buildEventQuery, buildMobileEventQuery } from '../../utils/proactiveAssistant.mjs';

function send(path, method, data, callback, failCallback) {
  const request = RequestService.sendRequest()
    .url(`${getServiceUrl()}${path}`)
    .method(method)
    .success(res => {
      RequestService.clearRequestTime();
      callback(res);
    })
    .fail(error => {
      RequestService.clearRequestTime();
      if (failCallback) failCallback(error);
    })
    .networkFail(error => {
      if (failCallback) failCallback(error);
    });
  if (data !== undefined) request.data(data);
  return request.send();
}

export default {
  getMonitors(deviceId, callback, failCallback) {
    return send(`/device/proactive/monitors/${encodeURIComponent(deviceId)}`, 'GET', undefined, callback, failCallback);
  },
  updateMonitors(deviceId, data, callback, failCallback) {
    return send(`/device/proactive/monitors/${encodeURIComponent(deviceId)}`, 'PUT', data, callback, failCallback);
  },
  getClassifierModel(callback, failCallback) {
    return send('/proactive/classifier/model', 'GET', undefined, callback, failCallback);
  },
  updateClassifierModel(data, callback, failCallback) {
    return send('/proactive/classifier/model', 'PUT', data, callback, failCallback);
  },
  testClassifierModel(callback, failCallback) {
    return send('/proactive/classifier/model/test', 'POST', undefined, callback, failCallback);
  },
  getExternalMonitoringSetting(callback, failCallback) {
    return send('/proactive/settings/external-monitoring', 'GET', undefined, callback, failCallback);
  },
  updateExternalMonitoringSetting(data, callback, failCallback) {
    return send('/proactive/settings/external-monitoring', 'PUT', data, callback, failCallback);
  },
  getPreference(deviceId, callback, failCallback) {
    return send(`/device/proactive/preferences/${encodeURIComponent(deviceId)}`, 'GET', undefined, callback, failCallback);
  },
  updatePreference(deviceId, data, callback, failCallback) {
    return send(`/device/proactive/preferences/${encodeURIComponent(deviceId)}`, 'PUT', data, callback, failCallback);
  },
  silentToday(deviceId, callback, failCallback) {
    return send(`/device/proactive/preferences/${encodeURIComponent(deviceId)}/today-silent`, 'PUT', undefined, callback, failCallback);
  },
  getEvents(filters, callback, failCallback) {
    return send(`/device/proactive/events?${buildEventQuery(filters)}`, 'GET', undefined, callback, failCallback);
  },
  getMobileEvents(filters, callback, failCallback) {
    return send(`/mobile/events/audit?${buildMobileEventQuery(filters)}`, 'GET', undefined, callback, failCallback);
  },
  getMobileAlertSettings(instanceId, callback, failCallback) {
    return send(`/mobile/events/audit/settings?mobile_instance_id=${encodeURIComponent(instanceId)}`, 'GET', undefined, callback, failCallback);
  },
  updateMobileAlertSettings(instanceId, data, callback, failCallback) {
    return send(`/mobile/events/audit/settings?mobile_instance_id=${encodeURIComponent(instanceId)}`, 'PUT', data, callback, failCallback);
  },
  getHabits(deviceId, callback, failCallback) {
    const query = new URLSearchParams({ device_id: deviceId }).toString();
    return send(`/device/proactive/habits?${query}`, 'GET', undefined, callback, failCallback);
  },
  deleteHabit(habitId, callback, failCallback) {
    return send(`/device/proactive/habits/${encodeURIComponent(habitId)}`, 'DELETE', undefined, callback, failCallback);
  },
};
