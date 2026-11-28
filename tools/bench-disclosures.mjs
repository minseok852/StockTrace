import http from 'node:http';
import { performance } from 'node:perf_hooks';
import { setTimeout as sleep } from 'node:timers/promises';

const port = Number(process.env.PORT ?? 8080);
const agent = new http.Agent({ keepAlive: true, maxSockets: 1000 });

function getPage(path, onSent = () => {}) {
  return new Promise((resolve, reject) => {
    const request = http.get({
      hostname: '127.0.0.1', port, path, agent, signal: AbortSignal.timeout(10000),
    }, response => {
      const chunks = [];
      response.on('data', chunk => chunks.push(chunk));
      response.on('error', reject);
      response.on('end', () => {
        try {
          if (response.statusCode !== 200) throw Error('HTTP ' + response.statusCode);
          const page = JSON.parse(Buffer.concat(chunks).toString('utf8'));
          if (!Array.isArray(page.items) || !Number.isInteger(page.counts?.total)) throw Error('Invalid response');
          resolve(page);
        } catch (error) { reject(error); }
      });
    });
    request.once('finish', onSent);
    request.on('error', reject);
  });
}

async function measure(path, rate, seconds) {
  const target = rate * seconds;
  const jobs = [];
  const latency = [];
  const errors = {};
  let sentInWindow = 0;
  let successInWindow = 0;
  let ok = 0;
  const start = performance.now();
  const end = start + seconds * 1000;

  async function send() {
    const begin = performance.now();
    try {
      await getPage(path, () => { if (performance.now() < end) sentInWindow++; });
      latency.push(performance.now() - begin);
      ok++;
      if (performance.now() < end) successInWindow++;
    } catch (error) {
      const reason = error.code ?? error.message;
      errors[reason] = (errors[reason] ?? 0) + 1;
    }
  }

  // Schedule arrivals independently of completion. Count slots missed by the driver.
  for (let i = 0; i < target; i++) {
    const due = start + i * 1000 / rate;
    while (performance.now() < due) await sleep(Math.max(1, due - performance.now()));
    if (performance.now() >= end) break;
    jobs.push(send());
  }
  await Promise.all(jobs);
  const elapsed = Math.max(seconds, (performance.now() - start) / 1000);
  latency.sort((a, b) => a - b);
  function percentile(p) {
    return latency.length ? Number(latency[Math.ceil(latency.length * p) - 1].toFixed(2)) : null;
  }
  const report = {
    path, seconds, targetTps: rate, scheduled: target, started: jobs.length,
    sentTpsInWindow: sentInWindow / seconds, successTpsInWindow: successInWindow / seconds,
    successTpsIncludingDrain: Number((ok / elapsed).toFixed(2)), success: ok,
    failed: jobs.length - ok, missed: target - jobs.length, errors,
    p50Ms: percentile(0.5), p95Ms: percentile(0.95), p99Ms: percentile(0.99),
  };
  console.log(JSON.stringify(report));
  if (report.failed || report.missed) process.exitCode = 1;
}

try {
  const listing = '/api/v1/disclosures';
  const sample = await getPage(listing + '?limit=2');
  if (!sample.items.length) throw Error('Load a nonempty disclosure snapshot first.');
  console.log('Snapshot filings: ' + sample.counts.total);
  await measure(listing + '?limit=20', 100, 60);
  await measure(listing + '?limit=2', 500, 10);
} catch (error) {
  console.error('Disclosure benchmark: ' + (error.code ?? error.message));
  process.exitCode = 1;
} finally {
  agent.destroy();
}
