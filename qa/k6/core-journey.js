import http from 'k6/http';
import { check, fail, group, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const PROFILE = __ENV.PROFILE || 'smoke';
const BASE_URL = (__ENV.BASE_URL || 'http://127.0.0.1:8080').replace(/\/$/, '');
const ALLOW_REMOTE = (__ENV.ALLOW_REMOTE || 'false').toLowerCase() === 'true';
const THINK_TIME_SECONDS = Number(__ENV.THINK_TIME_SECONDS || '0.2');
const ACCESS_TOKENS = (__ENV.ACCESS_TOKENS || '')
  .split(',')
  .map((token) => token.trim())
  .filter((token) => token.length > 0);

const PROFILE_CONFIG = {
  smoke: {
    scenario: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 1,
      maxDuration: '1m',
    },
  },
  baseline: {
    scenario: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '15s', target: 5 },
        { duration: '45s', target: 10 },
        { duration: '15s', target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },
  stress: {
    scenario: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '20s', target: 10 },
        { duration: '40s', target: 25 },
        { duration: '40s', target: 50 },
        { duration: '20s', target: 0 },
      ],
      gracefulRampDown: '15s',
    },
  },
};

if (!PROFILE_CONFIG[PROFILE]) {
  throw new Error(`지원하지 않는 PROFILE입니다: ${PROFILE}`);
}

const isLoopback = /^https?:\/\/(127\.0\.0\.1|localhost)(:\d+)?$/.test(BASE_URL);
if (!isLoopback && !ALLOW_REMOTE) {
  throw new Error('원격 서버 실행은 ALLOW_REMOTE=true를 명시해야 합니다.');
}
if (!isLoopback && ACCESS_TOKENS.length === 0) {
  throw new Error('원격 서버에서는 dev-login 대신 ACCESS_TOKENS가 필요합니다.');
}

const businessFailures = new Rate('business_failures');
const journeySuccess = new Rate('journey_success');
const journeyDuration = new Trend('journey_duration', true);
let loggedFailures = 0;
let vuAccessToken = null;

export const options = {
  scenarios: {
    core_journey: PROFILE_CONFIG[PROFILE].scenario,
  },
  thresholds: {
    checks: ['rate>0.99'],
    http_req_failed: ['rate<0.01'],
    'http_req_duration{expected_response:true}': ['p(95)<500', 'p(99)<1000'],
    business_failures: ['rate<0.01'],
    journey_success: ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  tags: {
    profile: PROFILE,
    test_type: 'core_qa_journey',
  },
};

function params(token, endpoint) {
  const headers = {
    Accept: 'application/json',
    'Content-Type': 'application/json',
  };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  return {
    headers,
    tags: {
      endpoint,
      name: endpoint,
    },
  };
}

function recordStatus(response, expectedStatus, endpoint) {
  const ok = check(response, {
    [`${endpoint} returns ${expectedStatus}`]: (value) => value.status === expectedStatus,
  });
  businessFailures.add(!ok, { endpoint });
  if (!ok && loggedFailures < 5) {
    console.error(`${endpoint} failed: status=${response.status}`);
    loggedFailures += 1;
  }
  return ok;
}

function parseBody(response, endpoint) {
  try {
    return response.json();
  } catch (error) {
    businessFailures.add(true, { endpoint });
    if (loggedFailures < 5) {
      console.error(`${endpoint} returned invalid JSON: ${error}`);
      loggedFailures += 1;
    }
    return null;
  }
}

function recordCondition(value, label, endpoint) {
  const ok = check(value, {
    [label]: (condition) => condition === true,
  });
  businessFailures.add(!ok, { endpoint });
  return ok;
}

function todayInKst() {
  const kstOffsetMs = 9 * 60 * 60 * 1000;
  return new Date(Date.now() + kstOffsetMs).toISOString().slice(0, 10);
}

function createLocalUser() {
  const endpoint = 'POST /api/v1/auth/dev-login';
  const response = http.post(`${BASE_URL}/api/v1/auth/dev-login`, '{}', params(null, endpoint));
  if (!recordStatus(response, 200, endpoint)) {
    fail('k6 테스트 사용자 생성에 실패했습니다.');
  }

  const body = parseBody(response, endpoint);
  if (!body || !body.accessToken) {
    fail('dev-login 응답에 accessToken이 없습니다.');
  }
  return body.accessToken;
}

function accessTokenForVu() {
  if (vuAccessToken) {
    return vuAccessToken;
  }

  if (ACCESS_TOKENS.length > 0) {
    vuAccessToken = ACCESS_TOKENS[(__VU - 1) % ACCESS_TOKENS.length];
  } else {
    vuAccessToken = createLocalUser();
  }
  return vuAccessToken;
}

export default function coreJourney() {
  const token = accessTokenForVu();
  const today = todayInKst();
  const runId = `${__VU}-${__ITER}-${Date.now()}`;
  const startedAt = Date.now();

  let journeyOk = true;
  let routineId = null;
  let routineCompleted = false;
  let todoId = null;
  let todoCompleted = false;

  group('member read', () => {
    const endpoint = 'GET /api/v1/me';
    const response = http.get(`${BASE_URL}/api/v1/me`, params(token, endpoint));
    journeyOk = recordStatus(response, 200, endpoint) && journeyOk;
  });

  group('routine lifecycle', () => {
    const createEndpoint = 'POST /api/v1/routines';
    const createResponse = http.post(
      `${BASE_URL}/api/v1/routines`,
      JSON.stringify({
        title: `k6-routine-${runId}`,
        categoryId: null,
        authType: 'CHECK',
        repeatType: 'DAILY',
        repeatDays: null,
        scheduledTime: null,
        startsOn: today,
        endsOn: today,
      }),
      params(token, createEndpoint),
    );
    const created = recordStatus(createResponse, 201, createEndpoint);
    journeyOk = created && journeyOk;
    if (!created) {
      return;
    }

    const routine = parseBody(createResponse, createEndpoint);
    routineId = routine ? routine.id : null;
    const hasRoutineId = recordCondition(
      Number.isInteger(routineId),
      'routine create returns id',
      createEndpoint,
    );
    journeyOk = hasRoutineId && journeyOk;
    if (!hasRoutineId) {
      return;
    }

    const completeEndpoint = 'POST /api/v1/routines/{id}/logs';
    const completeResponse = http.post(
      `${BASE_URL}/api/v1/routines/${routineId}/logs`,
      JSON.stringify({ routineDate: today }),
      params(token, completeEndpoint),
    );
    routineCompleted = recordStatus(completeResponse, 201, completeEndpoint);
    journeyOk = routineCompleted && journeyOk;

    const readEndpoint = 'GET /api/v1/routines/{id}';
    const readResponse = http.get(
      `${BASE_URL}/api/v1/routines/${routineId}`,
      params(token, readEndpoint),
    );
    journeyOk = recordStatus(readResponse, 200, readEndpoint) && journeyOk;
  });

  group('todo lifecycle', () => {
    const createEndpoint = 'POST /api/v1/todos';
    const createResponse = http.post(
      `${BASE_URL}/api/v1/todos`,
      JSON.stringify({
        title: `k6-todo-${runId}`,
        description: 'k6 core QA journey',
        categoryId: null,
        dueDate: today,
      }),
      params(token, createEndpoint),
    );
    const created = recordStatus(createResponse, 201, createEndpoint);
    journeyOk = created && journeyOk;
    if (!created) {
      return;
    }

    const todo = parseBody(createResponse, createEndpoint);
    todoId = todo ? todo.id : null;
    const hasTodoId = recordCondition(Number.isInteger(todoId), 'todo create returns id', createEndpoint);
    journeyOk = hasTodoId && journeyOk;
    if (!hasTodoId) {
      return;
    }

    const completeEndpoint = 'POST /api/v1/todos/{id}/complete';
    const completeResponse = http.post(
      `${BASE_URL}/api/v1/todos/${todoId}/complete`,
      null,
      params(token, completeEndpoint),
    );
    todoCompleted = recordStatus(completeResponse, 201, completeEndpoint);
    journeyOk = todoCompleted && journeyOk;
  });

  group('today read', () => {
    const endpoint = 'GET /api/v1/today';
    const response = http.get(`${BASE_URL}/api/v1/today`, params(token, endpoint));
    const readOk = recordStatus(response, 200, endpoint);
    journeyOk = readOk && journeyOk;
    if (!readOk) {
      return;
    }

    const body = parseBody(response, endpoint);
    const completedCount = body && body.summary ? body.summary.completedCount : null;
    const summaryOk = recordCondition(
      Number.isInteger(completedCount) && completedCount >= 2,
      'today summary includes completed routine and todo',
      endpoint,
    );
    journeyOk = summaryOk && journeyOk;
  });

  group('cleanup', () => {
    if (todoId !== null && todoCompleted) {
      const cancelEndpoint = 'DELETE /api/v1/todos/{id}/complete';
      const cancelResponse = http.del(
        `${BASE_URL}/api/v1/todos/${todoId}/complete`,
        null,
        params(token, cancelEndpoint),
      );
      journeyOk = recordStatus(cancelResponse, 200, cancelEndpoint) && journeyOk;
    }
    if (todoId !== null) {
      const deleteEndpoint = 'DELETE /api/v1/todos/{id}';
      const deleteResponse = http.del(
        `${BASE_URL}/api/v1/todos/${todoId}`,
        null,
        params(token, deleteEndpoint),
      );
      journeyOk = recordStatus(deleteResponse, 204, deleteEndpoint) && journeyOk;
    }

    if (routineId !== null && routineCompleted) {
      const cancelEndpoint = 'DELETE /api/v1/routines/{id}/logs';
      const cancelResponse = http.del(
        `${BASE_URL}/api/v1/routines/${routineId}/logs?date=${today}`,
        null,
        params(token, cancelEndpoint),
      );
      journeyOk = recordStatus(cancelResponse, 200, cancelEndpoint) && journeyOk;
    }
    if (routineId !== null) {
      const deleteEndpoint = 'DELETE /api/v1/routines/{id}';
      const deleteResponse = http.del(
        `${BASE_URL}/api/v1/routines/${routineId}`,
        null,
        params(token, deleteEndpoint),
      );
      journeyOk = recordStatus(deleteResponse, 204, deleteEndpoint) && journeyOk;
    }
  });

  journeyDuration.add(Date.now() - startedAt);
  journeySuccess.add(journeyOk);
  sleep(THINK_TIME_SECONDS);
}
