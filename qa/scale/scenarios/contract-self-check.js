import { check } from 'k6';
import {
  isAllowedBaseUrl,
  isCompletionBodyValid,
  isDuplicateConflictBodyValid,
  isMeBodyValid,
  isTodayCategoriesValid,
  isTodayProgressRateValid,
  isTodayStreakValid,
  isTodaySummaryCountValid,
  isTodaySummaryShapeValid,
} from './shared.js';

export const options = { vus: 1, iterations: 1, thresholds: { checks: ['rate==1'] } };

export default function contractSelfCheck() {
  const validToday = {
    date: todayInKst(),
    categories: [
      {
        categoryId: null,
        routines: [{ id: 1, title: 'routine', completed: true }],
        todos: [{ id: 2, title: 'todo', status: 'PENDING' }],
      },
    ],
    summary: {
      completedCount: 1,
      remainingCount: 1,
      progressRate: 0.5,
    },
    streak: {
      currentDays: 0,
      longestDays: 0,
      lastCompletedDate: null,
    },
  };
  const malformedToday = {
    date: todayInKst(),
    categories: [
      {
        categoryId: null,
        routines: [{ id: 1, title: 'routine', completed: true }],
        todos: [{ id: 2, title: 'todo', status: 'PENDING' }],
      },
    ],
    summary: {
      completedCount: 0,
      remainingCount: 0,
      progressRate: 0,
    },
    streak: {},
  };

  check(null, {
    'accepts actual me userId contract': () => isMeBodyValid({ userId: 7 }, { id: 7 }),
    'rejects legacy me id field': () => !isMeBodyValid({ id: 7 }, { id: 7 }),
    'rejects wrong me userId': () => !isMeBodyValid({ userId: 8 }, { id: 7 }),
    'accepts complete response contract': () => isCompletionBodyValid({
      id: 100,
      status: 'COMPLETED',
      completedAt: new Date().toISOString(),
      rewardAmount: 10,
    }, 100),
    'rejects wrong complete todo id': () => !isCompletionBodyValid({
      id: 101,
      status: 'COMPLETED',
      completedAt: new Date().toISOString(),
      rewardAmount: 10,
    }, 100),
    'rejects malformed complete reward': () => !isCompletionBodyValid({
      id: 100,
      status: 'COMPLETED',
      completedAt: new Date().toISOString(),
      rewardAmount: 11,
    }, 100),
    'accepts documented duplicate conflict code': () => isDuplicateConflictBodyValid({ code: 'TODO_ALREADY_COMPLETED' }),
    'rejects wrong duplicate conflict code': () => !isDuplicateConflictBodyValid({ code: 'ALREADY_COMPLETED' }),
    'accepts today summary DTO arithmetic': () => (
      isTodayCategoriesValid(validToday)
      && isTodaySummaryShapeValid(validToday)
      && isTodaySummaryCountValid(validToday)
      && isTodayProgressRateValid(validToday)
      && isTodayStreakValid(validToday)
    ),
    'rejects malformed today summary arithmetic': () => !isTodaySummaryCountValid(malformedToday),
    'rejects remote base url': () => !isAllowedBaseUrl('https://api.example.com'),
    'accepts loopback base url': () => isAllowedBaseUrl('http://127.0.0.1:18080'),
  });
}

function todayInKst() {
  const kstOffsetMs = 9 * 60 * 60 * 1000;
  return new Date(Date.now() + kstOffsetMs).toISOString().slice(0, 10);
}
