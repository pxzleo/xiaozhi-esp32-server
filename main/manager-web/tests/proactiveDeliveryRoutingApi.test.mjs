import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const apiSource = await readFile(new URL('../src/apis/module/proactive.js', import.meta.url), 'utf8');

test('delivery routing uses the frozen account-level GET and PUT endpoints', () => {
  assert.match(apiSource, /getDeliveryRouting\(callback, failCallback\)[\s\S]*?'\/device\/proactive\/delivery-routing', 'GET'/);
  assert.match(apiSource, /updateDeliveryRouting\(data, callback, failCallback\)[\s\S]*?'\/device\/proactive\/delivery-routing', 'PUT', data/);
});
