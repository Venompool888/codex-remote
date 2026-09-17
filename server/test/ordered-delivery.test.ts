import test from 'node:test';
import assert from 'node:assert/strict';
import {setImmediate} from 'node:timers/promises';
import {OrderedDelivery} from '../src/ordered-delivery.js';

test('slow projection retains strict order and releases its capacity', async () => {
  let release!: () => void;
  const gate = new Promise<void>(resolve => { release = resolve; });
  const seen: number[] = [];
  const failures: boolean[] = [];
  const queue = new OrderedDelivery(value => failures.push(value), 2, 10);
  queue.enqueue(6, async () => { await gate; seen.push(1); });
  queue.enqueue(4, async () => { seen.push(2); });
  assert.deepEqual(seen, []);
  release(); await setImmediate();
  queue.enqueue(10, async () => { seen.push(3); });
  await setImmediate();
  assert.deepEqual(seen, [1, 2, 3]);
  assert.deepEqual(failures, []);
});

test('overflow accounts for active work, clears queued jobs and stops accepting', async () => {
  for (const limits of [[2, 100], [10, 2]]) {
    let release!: () => void;
    const gate = new Promise<void>(resolve => { release = resolve; });
    const seen: number[] = []; const failures: boolean[] = [];
    const queue = new OrderedDelivery(value => failures.push(value), ...limits as [number, number]);
    queue.enqueue(1, async () => { await gate; seen.push(1); });
    queue.enqueue(1, async () => { seen.push(2); });
    queue.enqueue(1, async () => { seen.push(3); });
    queue.enqueue(0, async () => { seen.push(4); });
    release(); await setImmediate();
    assert.deepEqual(seen, [1]);
    assert.deepEqual(failures, [true]);
  }
});

test('disconnect and projection failure discard remaining work', async () => {
  for (const disconnect of [false, true]) {
    let release!: () => void;
    const gate = new Promise<void>(resolve => { release = resolve; });
    const failures: boolean[] = []; let dispatched = false;
    const queue = new OrderedDelivery(value => failures.push(value));
    queue.enqueue(1, async () => { await gate; if (!disconnect) throw Error('private internal failure'); });
    queue.enqueue(1, async () => { dispatched = true; });
    if (disconnect) queue.stop();
    release(); await setImmediate();
    assert.equal(dispatched, false);
    assert.deepEqual(failures, disconnect ? [] : [false]);
  }
});
