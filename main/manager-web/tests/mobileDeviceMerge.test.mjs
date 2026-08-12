import assert from 'node:assert/strict';
import test from 'node:test';

import { buildMobileMergeSelection } from '../src/utils/mobileDeviceMerge.mjs';

test('chooses the most recently connected selected Android record as canonical', () => {
  const selection = buildMobileMergeSelection([
    { device_id: 'old', model: 'android-mobile', selected: true, lastConnectedAtTimestamp: 10 },
    { device_id: 'new', model: 'android-mobile', selected: true, lastConnectedAtTimestamp: 20 },
    { device_id: 'speaker', model: 'esp32', selected: true, lastConnectedAtTimestamp: 30 },
  ]);

  assert.deepEqual(selection, {
    canonicalDeviceId: 'new',
    duplicateDeviceIds: ['old'],
    count: 2,
  });
});

test('requires at least two selected Android records', () => {
  assert.equal(buildMobileMergeSelection([
    { device_id: 'one', model: 'android-mobile', selected: true },
    { device_id: 'speaker', model: 'esp32', selected: true },
  ]), null);
});
