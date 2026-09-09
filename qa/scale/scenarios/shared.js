import http from 'k6/http';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { check, fail } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

export const ENDPOINTS = Object.freeze({
  me: {
    tag: 'me',
    method: 'GET',
    path: '/api/v1/me',
    successStatus: 200,
  },
  today: {
    tag: 'today',
    method: 'GET',
    path: '/api/v1/today',
    successStatus: 200,
  },
  complete: {
    tag: 'complete',
    method: 'POST',
    path: (todoId) => `/api/v1/todos/${todoId}/complete`,
    successStatus: 201,
  },
});

export const sentRequests = new Counter('sent_requests');
export const businessSuccess = new Counter('business_success');
export const businessSuccessRate = new Rate('business_success_rate');
export const validOutcomes = new Counter('valid_outcomes');
export const validOutcomeRate = new Rate('valid_outcome_rate');
export const unexpectedFailure = new Rate('unexpected_failure');
export const expectedConflicts = new Counter('expected_conflicts');
export const rejectedRequests = new Counter('rejected_requests');
export const rejectedRequestRate = new Rate('rejected_request_rate');
export const responseLatency = new Trend('response_latency', true);
export const successfulLatency = new Trend('successful_latency', true);
export const completionSuccess = new Counter('completion_success');
export const rewardAmount = new Counter('reward_amount');

const DUPLICATE_TODO_CODE = 'TODO_ALREADY_COMPLETED';
const STATUS_CLASS_UNKNOWN = 'unknown';

let loggedFailures = 0;

export function loadConfig(profile) {
  const rate = readPositiveInteger('RATE');
  const durationSeconds = readPositiveInteger('DURATION_SECONDS');
  const preAllocatedVus = readPositiveInteger('PREALLOCATED_VUS');
  const maxVus = readPositiveInteger('MAX_VUS', preAllocatedVus);
  const baseUrl = readBaseUrl();
  const fixturePath = readRequiredEnv('FIXTURE_PATH');
  const summaryPath = readRequiredEnv('SUMMARY_PATH');
  const fixture = readFixture(fixturePath);

  if (maxVus !== preAllocatedVus) {
    throw new Error('MAX_VUS는 고정 VU 풀 검증을 위해 PREALLOCATED_VUS와 같아야 합니다.');
  }

  return {
    profile,
    rate,
    durationSeconds,
    preAllocatedVus,
    maxVus,
    baseUrl,
    fixturePath,
    summaryPath,
    fixture,
  };
}

export function constantArrivalOptions(config, thresholds) {
  return {
    scenarios: {
      [config.profile]: {
        executor: 'constant-arrival-rate',
        rate: config.rate,
        timeUnit: '1s',
        duration: `${config.durationSeconds}s`,
        preAllocatedVUs: config.preAllocatedVus,
        maxVUs: config.maxVus,
      },
    },
    thresholds,
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
    noConnectionReuse: false,
    userAgent: `rougether-k6-${config.profile}`,
    tags: {
      profile: config.profile,
      test_type: 'scale_qa',
    },
  };
}

export function thresholdsFor(profile) {
  const latency = latencyThresholds(profile);
  const thresholds = {
    checks: ['rate>=0.999'],
    dropped_iterations: ['count==0'],
    sent_requests: ['count>0'],
    'business_success{endpoint:me}': ['count>=0'],
    'business_success{endpoint:today}': ['count>=0'],
    'business_success{endpoint:complete}': ['count>=0'],
    valid_outcome_rate: ['rate>=0.999'],
    unexpected_failure: ['rate<=0.001'],
    rejected_request_rate: ['rate<=0.001'],
    response_latency: latency,
  };
  if (profile !== 'contention') {
    thresholds.business_success_rate = ['rate>=0.999'];
  }
  if (profile === 'read' || profile === 'mixed') {
    thresholds['sent_requests{endpoint:me}'] = ['count>=0'];
    thresholds['sent_requests{endpoint:today}'] = ['count>=0'];
    thresholds['business_success_rate{endpoint:me}'] = ['rate>=0.999'];
    thresholds['business_success_rate{endpoint:today}'] = ['rate>=0.999'];
    thresholds['valid_outcome_rate{endpoint:me}'] = ['rate>=0.999'];
    thresholds['valid_outcome_rate{endpoint:today}'] = ['rate>=0.999'];
    thresholds['response_latency{endpoint:me}'] = latencyForRead(profile);
    thresholds['response_latency{endpoint:today}'] = latencyForRead(profile);
  }
  if (profile === 'write' || profile === 'mixed' || profile === 'contention') {
    thresholds['sent_requests{endpoint:complete}'] = ['count>0'];
    if (profile !== 'contention') {
      thresholds['business_success_rate{endpoint:complete}'] = ['rate>=0.999'];
    }
    thresholds['valid_outcome_rate{endpoint:complete}'] = ['rate>=0.999'];
    thresholds['completion_success{endpoint:complete}'] = ['count>=0'];
    thresholds['reward_amount{endpoint:complete}'] = ['count>=0'];
    thresholds['response_latency{endpoint:complete}'] = latencyForWrite(profile);
  }
  if (profile === 'contention') {
    thresholds['expected_conflicts{endpoint:complete}'] = ['count>=0'];
  }
  return thresholds;
}

export function iterationInTest() {
  return exec.scenario.iterationInTest;
}

export function userForRead(fixture, iteration) {
  const users = fixture.users;
  return users[iteration % users.length];
}

export function todoForUniqueWrite(fixture, ordinal) {
  const todo = todoAt(fixture, ordinal);
  if (!todo) {
    fail(`FIXTURE_PATH 투두가 부족합니다. write ordinal=${ordinal}, todoCount=${fixture.todoCount}`);
  }
  return todo;
}

export function todoForContention(fixture, iteration, hotTodoCount) {
  if (!Number.isInteger(hotTodoCount) || hotTodoCount < 1) {
    throw new Error('HOT_TODOS는 1 이상의 정수여야 합니다.');
  }
  if (fixture.todoCount < hotTodoCount) {
    throw new Error(`HOT_TODOS=${hotTodoCount}인데 fixture todoCount=${fixture.todoCount}입니다.`);
  }
  return todoAt(fixture, iteration % hotTodoCount);
}

export function userForTodo(fixture, todo, ordinal) {
  return fixture.users[ordinal % fixture.users.length];
}

export function requestJson(config, endpoint, token, pathOverride = null) {
  const path = pathOverride || endpoint.path;
  const url = `${config.baseUrl}${path}`;
  const tags = {
    endpoint: endpoint.tag,
    name: endpoint.tag,
  };
  const params = {
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
    redirects: 0,
    timeout: '5s',
    responseType: 'text',
    tags,
  };

  const startedAt = Date.now();
  const response = endpoint.method === 'POST'
    ? http.post(url, null, params)
    : http.get(url, params);
  const elapsedMs = Date.now() - startedAt;
  const responseTags = {
    ...tags,
    status_class: statusClass(response.status),
  };
  materializeZeroCounters(responseTags);
  sentRequests.add(1, responseTags);
  responseLatency.add(elapsedMs, responseTags);
  const rejected = response.status === 429 || response.status === 503;
  rejectedRequestRate.add(rejected, responseTags);
  if (rejected) {
    rejectedRequests.add(1, responseTags);
  }
  return { response, tags: responseTags, elapsedMs };
}

export function requestCompletion(config, user, todo) {
  return requestJson(config, ENDPOINTS.complete, user.token, ENDPOINTS.complete.path(todo.id));
}

export function recordRead(response, endpoint, expectedUser, elapsedMs) {
  const statusOk = response.status === endpoint.successStatus;
  let bodyOk = false;
  if (statusOk) {
    const body = parseJson(response, endpoint.tag);
    bodyOk = checkReadBody(body, endpoint.tag, expectedUser);
  } else {
    check(response, {
      [`${endpoint.tag} returns ${endpoint.successStatus}`]: (value) => value.status === endpoint.successStatus,
    });
  }
  const ok = statusOk && bodyOk;
  recordOutcome(ok, ok, !ok, response, endpoint.tag, elapsedMs);
  return ok;
}

function checkReadBody(body, endpointTag, expectedUser) {
  if (endpointTag === ENDPOINTS.me.tag) {
    return check(body, {
      'me response has expected userId': (value) => isMeBodyValid(value, expectedUser),
    });
  }
  return check(body, {
    'today response date is today in KST': (value) => isTodayDateValid(value),
    'today response has categories array': (value) => isTodayCategoriesValid(value),
    'today response summary has counts': (value) => isTodaySummaryShapeValid(value),
    'today summary counts match listed items': (value) => isTodaySummaryCountValid(value),
    'today progressRate matches counts': (value) => isTodayProgressRateValid(value),
    'today response has streak object': (value) => isTodayStreakValid(value),
  });
}

export function recordCompletion(response, expectedConflict = false, expectedTodoId = null, elapsedMs = 0) {
  if (response.status === ENDPOINTS.complete.successStatus) {
    const body = parseJson(response, ENDPOINTS.complete.tag);
    const ok = check(body, {
      'complete response id matches requested todo': (value) => isCompletionBodyValid(value, expectedTodoId),
      'complete response status is COMPLETED': (value) => value && value.status === 'COMPLETED',
      'complete response has completedAt': (value) => typeof (value && value.completedAt) === 'string',
      'complete response rewardAmount is bounded': (value) => isRewardAmountValid(value),
    });
    recordOutcome(ok, ok, !ok, response, ENDPOINTS.complete.tag, elapsedMs);
    if (ok) {
      completionSuccess.add(1, { endpoint: ENDPOINTS.complete.tag });
      rewardAmount.add(body.rewardAmount, { endpoint: ENDPOINTS.complete.tag });
    }
    return ok;
  }

  if (expectedConflict && response.status === 409) {
    const body = parseJson(response, ENDPOINTS.complete.tag);
    const ok = check(body, {
      'duplicate complete returns TODO_ALREADY_COMPLETED': (value) => isDuplicateConflictBodyValid(value),
    });
    if (ok) {
      expectedConflicts.add(1, { endpoint: ENDPOINTS.complete.tag, status_class: '4xx' });
      recordOutcome(false, true, false, response, ENDPOINTS.complete.tag, elapsedMs);
      return true;
    }
  }

  return recordExpectedStatus(response, ENDPOINTS.complete.successStatus, ENDPOINTS.complete.tag, elapsedMs);
}

export function buildSummary(config, data, extra = {}) {
  return {
    profile: config.profile,
    generatedAt: new Date().toISOString(),
    config: {
      rate: config.rate,
      durationSeconds: config.durationSeconds,
      preAllocatedVUs: config.preAllocatedVus,
      maxVUs: config.maxVus,
      baseUrl: config.baseUrl,
      fixturePath: config.fixturePath,
    },
    fixture: {
      userCount: config.fixture.users.length,
      userStartId: config.fixture.userStartId,
      usersCount: config.fixture.usersCount,
      todoCount: config.fixture.todoCount,
      todoStartId: config.fixture.todoStartId,
      date: config.fixture.date,
      schema: 'users[{id,token}], todoStartId, todoCount',
    },
    audit: extra.audit || {},
    metrics: extractMetrics(data),
    root: data.root_group,
  };
}

export function summaryOutput(config, data, extra = {}) {
  const summary = buildSummary(config, data, extra);
  return {
    [config.summaryPath]: `${JSON.stringify(summary, null, 2)}\n`,
    stdout: `${config.profile}: sent=${metricCount(summary.metrics.sent_requests)} business_success_rate=${metricRate(summary.metrics.business_success_rate)} unexpected_failure=${metricRate(summary.metrics.unexpected_failure)} dropped=${metricCount(summary.metrics.dropped_iterations)}\n`,
  };
}

function readRequiredEnv(name) {
  const value = __ENV[name];
  if (!value || value.trim().length === 0) {
    throw new Error(`${name} 환경변수가 필요합니다.`);
  }
  return value.trim();
}

function readPositiveInteger(name, defaultValue = null) {
  const raw = __ENV[name];
  if ((raw === undefined || raw === '') && defaultValue !== null) {
    return defaultValue;
  }
  const value = Number(raw);
  if (!Number.isInteger(value) || value < 1) {
    throw new Error(`${name}는 1 이상의 정수여야 합니다.`);
  }
  return value;
}

function readBaseUrl() {
  const value = readRequiredEnv('BASE_URL').replace(/\/$/, '');
  if (!isAllowedBaseUrl(value)) {
    throw new Error('BASE_URL은 http://127.0.0.1:<port> 형식만 허용합니다.');
  }
  return value;
}

export function isAllowedBaseUrl(value) {
  return /^http:\/\/127\.0\.0\.1:[1-9][0-9]*$/.test(value);
}

function readFixture(path) {
  const users = new SharedArray(`scale-fixture-users:${path}`, () => {
    const parsed = parseFixture(path);
    if (!Array.isArray(parsed.users) || parsed.users.length === 0) {
      throw new Error('fixture.users에는 최소 1개의 {id, token}이 필요합니다.');
    }
    const userStartId = Number.isInteger(parsed.userStartId) ? parsed.userStartId : null;
    parsed.users.forEach((user, index) => {
      if (!user.token || typeof user.token !== 'string') {
        throw new Error(`fixture.users[${index}].token이 필요합니다.`);
      }
      if (userStartId !== null && user.id !== userStartId + index) {
        throw new Error(`fixture.users[${index}].id는 userStartId + index와 같아야 합니다.`);
      }
    });
    return parsed.users.map(normalizeUser);
  });
  if (users.length === 0) {
    throw new Error('fixture.users에는 최소 1개의 {id, token}이 필요합니다.');
  }
  const metadata = new SharedArray(`scale-fixture-metadata:${path}`, () => {
    const parsed = parseFixture(path);
    return [{
      userStartId: Number.isInteger(parsed.userStartId) ? parsed.userStartId : null,
      usersCount: Number.isInteger(parsed.usersCount) ? parsed.usersCount : null,
      todoStartId: parsed.todoStartId,
      todoCount: parsed.todoCount,
      date: typeof parsed.date === 'string' ? parsed.date : null,
    }];
  })[0];

  const userStartId = metadata.userStartId;
  const usersCount = metadata.usersCount;
  const todoStartId = metadata.todoStartId;
  const todoCount = metadata.todoCount;

  if (usersCount !== null && usersCount !== users.length) {
    throw new Error(`fixture.usersCount=${usersCount}인데 users.length=${users.length}입니다.`);
  }
  if (!Number.isInteger(todoStartId) || todoStartId < 1) {
    throw new Error('fixture.todoStartId는 1 이상의 정수여야 합니다.');
  }
  if (!Number.isInteger(todoCount) || todoCount < 0) {
    throw new Error('fixture.todoCount는 0 이상의 정수여야 합니다.');
  }

  return {
    users,
    userStartId,
    usersCount,
    todoStartId,
    todoCount,
    date: metadata.date,
  };
}

function parseFixture(path) {
  try {
    return JSON.parse(open(path));
  } catch (error) {
    throw new Error(`FIXTURE_PATH를 읽을 수 없습니다: ${error}`);
  }
}

function normalizeUser(user) {
  return {
    id: user.id === undefined ? null : user.id,
    token: user.token,
  };
}

function todoAt(fixture, ordinal) {
  if (!Number.isInteger(ordinal) || ordinal < 0) {
    return null;
  }
  if (ordinal >= fixture.todoCount) {
    return null;
  }
  return {
    id: fixture.todoStartId + ordinal,
  };
}

function recordExpectedStatus(response, expectedStatus, endpointTag, elapsedMs) {
  const ok = check(response, {
    [`${endpointTag} returns ${expectedStatus}`]: (value) => value.status === expectedStatus,
  });
  recordOutcome(ok, ok, !ok, response, endpointTag, elapsedMs);
  return ok;
}

function recordOutcome(businessOk, validOutcome, unexpected, response, endpointTag, elapsedMs) {
  const statusTags = {
    endpoint: endpointTag,
    status_class: statusClass(response.status),
  };
  businessSuccess.add(businessOk ? 1 : 0, statusTags);
  businessSuccessRate.add(businessOk, statusTags);
  validOutcomes.add(validOutcome ? 1 : 0, statusTags);
  validOutcomeRate.add(validOutcome, statusTags);
  unexpectedFailure.add(unexpected, statusTags);
  if (businessOk) {
    successfulLatency.add(elapsedMs, statusTags);
  } else if (!validOutcome) {
    logFailure(endpointTag, response);
  }
}

function parseJson(response, endpointTag) {
  try {
    return response.json();
  } catch (error) {
    logFailure(endpointTag, response, `invalid JSON: ${error}`);
    return null;
  }
}

function logFailure(endpointTag, response, detail = '') {
  if (loggedFailures >= 5) {
    return;
  }
  const suffix = detail ? ` ${detail}` : '';
  console.error(`${endpointTag} failed: status=${response.status}${suffix}`);
  loggedFailures += 1;
}

function materializeZeroCounters(tags) {
  businessSuccess.add(0, tags);
  validOutcomes.add(0, tags);
  expectedConflicts.add(0, tags);
  rejectedRequests.add(0, tags);
  completionSuccess.add(0, tags);
  rewardAmount.add(0, tags);
}

export function isMeBodyValid(value, expectedUser) {
  if (!value || !Number.isInteger(value.userId)) {
    return false;
  }
  return expectedUser.id === null || expectedUser.id === undefined || value.userId === expectedUser.id;
}

export function isCompletionBodyValid(value, expectedTodoId) {
  return value
    && value.id === expectedTodoId
    && value.status === 'COMPLETED'
    && typeof value.completedAt === 'string'
    && isRewardAmountValid(value);
}

export function isRewardAmountValid(value) {
  return Number.isInteger(value && value.rewardAmount)
    && value.rewardAmount >= 0
    && value.rewardAmount <= 10;
}

export function isDuplicateConflictBodyValid(value) {
  return value && value.code === DUPLICATE_TODO_CODE;
}

export function isTodayDateValid(value) {
  return value && value.date === todayInKst();
}

export function isTodayCategoriesValid(value) {
  return value && Array.isArray(value.categories);
}

export function isTodaySummaryShapeValid(value) {
  return value
    && value.summary
    && Number.isInteger(value.summary.completedCount)
    && Number.isInteger(value.summary.remainingCount)
    && typeof value.summary.progressRate === 'number';
}

export function isTodaySummaryCountValid(value) {
  if (!isTodayCategoriesValid(value) || !isTodaySummaryShapeValid(value)) {
    return false;
  }
  const counts = countTodayItems(value.categories);
  return value.summary.completedCount === counts.completed
    && value.summary.remainingCount === counts.remaining;
}

export function isTodayProgressRateValid(value) {
  if (!isTodaySummaryShapeValid(value)) {
    return false;
  }
  const total = value.summary.completedCount + value.summary.remainingCount;
  const expected = total === 0 ? 0 : value.summary.completedCount / total;
  return Math.abs(value.summary.progressRate - expected) < 0.000001;
}

export function isTodayStreakValid(value) {
  return value && typeof value.streak === 'object' && value.streak !== null;
}

function countTodayItems(categories) {
  return categories.reduce((acc, category) => {
    const routines = Array.isArray(category.routines) ? category.routines : [];
    const todos = Array.isArray(category.todos) ? category.todos : [];
    routines.forEach((routine) => {
      if (routine && routine.completed === true) {
        acc.completed += 1;
      } else {
        acc.remaining += 1;
      }
    });
    todos.forEach((todo) => {
      if (todo && todo.status === 'COMPLETED') {
        acc.completed += 1;
      } else {
        acc.remaining += 1;
      }
    });
    return acc;
  }, { completed: 0, remaining: 0 });
}

function todayInKst() {
  const kstOffsetMs = 9 * 60 * 60 * 1000;
  return new Date(Date.now() + kstOffsetMs).toISOString().slice(0, 10);
}

function statusClass(status) {
  if (!Number.isInteger(status) || status < 100) {
    return STATUS_CLASS_UNKNOWN;
  }
  return `${Math.floor(status / 100)}xx`;
}

function latencyThresholds(profile) {
  if (profile === 'read') {
    return ['p(95)<200', 'p(99)<500'];
  }
  if (profile === 'mixed') {
    return ['p(95)<300', 'p(99)<1000'];
  }
  return ['p(95)<500', 'p(99)<1000'];
}

function latencyForRead(profile) {
  return profile === 'read' ? ['p(95)<200', 'p(99)<500'] : latencyThresholds(profile);
}

function latencyForWrite(profile) {
  return profile === 'mixed' ? ['p(95)<500', 'p(99)<1000'] : latencyThresholds(profile);
}

function extractMetrics(data) {
  const extracted = {};
  Object.keys(data.metrics || {}).sort().forEach((name) => {
    extracted[name] = normalizeMetricValues(data.metrics[name]);
  });
  return extracted;
}

function normalizeMetricValues(metric) {
  if (!metric) {
    return {};
  }
  if (metric.values && typeof metric.values === 'object') {
    return metric.values;
  }
  const values = {};
  ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)', 'count', 'rate'].forEach((key) => {
    if (Number.isFinite(metric[key])) {
      values[key] = metric[key];
    }
  });
  return values;
}

function metricCount(metric) {
  return metric && Number.isFinite(metric.count) ? metric.count : 0;
}

function metricRate(metric) {
  return metric && Number.isFinite(metric.rate) ? metric.rate.toFixed(4) : 'n/a';
}
