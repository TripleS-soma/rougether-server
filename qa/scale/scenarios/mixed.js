import {
  ENDPOINTS,
  constantArrivalOptions,
  iterationInTest,
  loadConfig,
  recordCompletion,
  recordRead,
  requestCompletion,
  requestJson,
  summaryOutput,
  thresholdsFor,
  todoForUniqueWrite,
  userForRead,
  userForTodo,
} from './shared.js';

const config = loadConfig('mixed');

export const options = constantArrivalOptions(config, thresholdsFor('mixed'));

export default function mixedScenario() {
  const iteration = iterationInTest();
  const slot = iteration % 5;

  if (slot === 4) {
    const writeOrdinal = Math.floor(iteration / 5);
    const todo = todoForUniqueWrite(config.fixture, writeOrdinal);
    const user = userForTodo(config.fixture, todo, writeOrdinal);
    const { response, elapsedMs } = requestCompletion(config, user, todo);
    recordCompletion(response, false, todo.id, elapsedMs);
    return;
  }

  const endpoint = slot % 2 === 0 ? ENDPOINTS.me : ENDPOINTS.today;
  const user = userForRead(config.fixture, iteration);
  const { response, elapsedMs } = requestJson(config, endpoint, user.token);
  recordRead(response, endpoint, user, elapsedMs);
}

export function handleSummary(data) {
  return summaryOutput(config, data, {
    audit: {
      requestShape: 'four reads then one write by iterationInTest; reads alternate me/today',
      writeTodoIdFormula: 'todoStartId + floor(iterationInTest / 5) when iterationInTest % 5 == 4',
    },
  });
}
