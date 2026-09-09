import {
  ENDPOINTS,
  constantArrivalOptions,
  iterationInTest,
  loadConfig,
  recordCompletion,
  requestCompletion,
  summaryOutput,
  thresholdsFor,
  todoForUniqueWrite,
  userForTodo,
} from './shared.js';

const config = loadConfig('write');

export const options = constantArrivalOptions(config, thresholdsFor('write'));

export default function writeScenario() {
  const ordinal = iterationInTest();
  const todo = todoForUniqueWrite(config.fixture, ordinal);
  const user = userForTodo(config.fixture, todo, ordinal);
  const { response, elapsedMs } = requestCompletion(config, user, todo);
  recordCompletion(response, false, todo.id, elapsedMs);
}

export function handleSummary(data) {
  return summaryOutput(config, data, {
    audit: {
      requestShape: 'one unique todo completion request per iteration; fixture exhaustion fails the run',
      todoIdFormula: 'todoStartId + iterationInTest',
    },
  });
}
