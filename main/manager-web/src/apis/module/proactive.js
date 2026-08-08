import { getServiceUrl } from '../api';
import RequestService from '../httpRequest';
import { buildEventQuery } from '../../utils/proactiveAssistant.mjs';

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
  getHabits(deviceId, callback, failCallback) {
    const query = new URLSearchParams({ device_id: deviceId }).toString();
    return send(`/device/proactive/habits?${query}`, 'GET', undefined, callback, failCallback);
  },
  deleteHabit(habitId, callback, failCallback) {
    return send(`/device/proactive/habits/${encodeURIComponent(habitId)}`, 'DELETE', undefined, callback, failCallback);
  },
};
