import {
  ENDPOINTS,
  constantArrivalOptions,
  iterationInTest,
  loadConfig,
  recordCompletion,
  requestCompletion,
  summaryOutput,
  thresholdsFor,
  todoForContention,
  userForTodo,
} from './shared.js';

const config = loadConfig('contention');
const hotTodoCount = readHotTodoCount();

export const options = constantArrivalOptions(config, thresholdsFor('contention'));

export default function contentionScenario() {
  const iteration = iterationInTest();
  const todo = todoForContention(config.fixture, iteration, hotTodoCount);
  const user = userForTodo(config.fixture, todo, iteration % hotTodoCount);
  const { response, elapsedMs } = requestCompletion(config, user, todo);
  recordCompletion(response, true, todo.id, elapsedMs);
}

export function handleSummary(data) {
  return summaryOutput(config, data, {
    audit: {
      requestShape: 'one completion request per iteration against a bounded hot todo set; duplicate 409 TODO_ALREADY_COMPLETED is expected',
      hotTodoCount,
      expectedMaximumCompletedTodos: hotTodoCount,
    },
  });
}

function readHotTodoCount() {
  const raw = __ENV.HOT_TODOS || '5';
  const value = Number(raw);
  if (!Number.isInteger(value) || value < 1) {
    throw new Error('HOT_TODOS는 1 이상의 정수여야 합니다.');
  }
  return value;
}
