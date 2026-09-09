import { check } from 'k6';
import exec from 'k6/execution';
import {
  loadConfig,
  summaryOutput,
  userForRead,
} from './shared.js';

const config = loadConfig('fixture-init-self-check');

export const options = {
  scenarios: {
    fixture_init_self_check: {
      executor: 'per-vu-iterations',
      vus: config.preAllocatedVus,
      iterations: 1,
      maxDuration: `${Math.max(30, config.durationSeconds)}s`,
    },
  },
  thresholds: {
    checks: ['rate==1'],
    dropped_iterations: ['count==0'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  tags: {
    profile: config.profile,
    test_type: 'scale_fixture_init',
  },
};

export default function fixtureInitSelfCheck() {
  const userIndex = (exec.vu.idInTest - 1) % config.fixture.users.length;
  const user = userForRead(config.fixture, exec.vu.idInTest - 1);
  check(user, {
    'fixture user token is present': (value) => typeof value.token === 'string' && value.token.length > 0,
    'fixture user id matches contiguous range': (value) => (
      config.fixture.userStartId === null
      || value.id === config.fixture.userStartId + userIndex
    ),
  });
}

export function handleSummary(data) {
  return summaryOutput(config, data, {
    audit: {
      requestShape: 'fixture initialization self-check; one no-HTTP iteration per preallocated VU',
      preallocatedVuCount: config.preAllocatedVus,
    },
  });
}
