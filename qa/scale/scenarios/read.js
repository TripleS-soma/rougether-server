import {
  ENDPOINTS,
  constantArrivalOptions,
  iterationInTest,
  loadConfig,
  recordRead,
  requestJson,
  summaryOutput,
  thresholdsFor,
  userForRead,
} from './shared.js';

const config = loadConfig('read');

export const options = constantArrivalOptions(config, thresholdsFor('read'));

export default function readScenario() {
  const iteration = iterationInTest();
  const endpoint = iteration % 2 === 0 ? ENDPOINTS.me : ENDPOINTS.today;
  const user = userForRead(config.fixture, iteration);
  const { response, elapsedMs } = requestJson(config, endpoint, user.token);
  recordRead(response, endpoint, user, elapsedMs);
}

export function handleSummary(data) {
  return summaryOutput(config, data, {
    audit: {
      requestShape: 'one read request per iteration; endpoint alternates by iterationInTest parity',
    },
  });
}
