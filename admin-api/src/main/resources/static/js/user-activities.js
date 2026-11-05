(() => {
    'use strict';
    const $ = (id) => document.getElementById(id);
    const number = new Intl.NumberFormat('ko-KR');
    const time = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' });
    let page = 0;
    let data = null;
    let requestId = 0;
    let activeRequest = null;

    function element(tag, text, className) {
        const node = document.createElement(tag);
        if (text != null) node.textContent = text;
        if (className) node.className = className;
        return node;
    }

    function formatTime(value) {
        return value ? time.format(new Date(value)) : '—';
    }

    function renderUser(user) {
        const row = element('tr');
        const identity = element('td');
        identity.append(element('span', user.nickname || '닉네임 미설정', 'activity-user-name'));
        identity.append(element('span', `#${user.userId}`, 'activity-muted'));
        const activities = element('td');
        if (!user.features.length) {
            activities.append(element('span', '접속 기록만 확인', 'activity-muted'));
        } else {
            const pills = element('div', null, 'activity-pills');
            user.features.forEach((feature) => {
                const pill = element('span', `${feature.label} ${number.format(feature.recordCount)}건`, 'activity-pill');
                pill.title = feature.description;
                pills.append(pill);
            });
            activities.append(pills);
            const details = element('details', null, 'activity-details');
            details.append(element('summary', '기능별 시각과 집계 기준 보기'));
            const list = element('ul');
            user.features.forEach((feature) => {
                const item = element('li');
                item.append(element('strong', `${feature.label} · ${number.format(feature.recordCount)}건`));
                item.append(element('span', `첫 기록 ${formatTime(feature.firstRecordedAt)} / 마지막 기록 ${formatTime(feature.lastRecordedAt)} KST`, 'activity-muted'));
                item.append(element('span', feature.description, 'activity-muted'));
                list.append(item);
            });
            details.append(list);
            activities.append(details);
        }
        row.append(identity, activities, element('td', `${number.format(user.actionRecords)}건`),
            element('td', user.lastRecordedAt ? formatTime(user.lastRecordedAt) : '시각 미수집'));
        return row;
    }

    function render(result) {
        data = result;
        page = result.page;
        $('date').value = result.date;
        $('date').min = result.minDate;
        $('date').max = result.maxDate;
        const selectedFeature = $('feature').value;
        $('feature').replaceChildren(element('option', '전체 기능'));
        $('feature').firstChild.value = '';
        result.availableFeatures.forEach((feature) => {
            const option = element('option', feature.label);
            option.value = feature.code;
            option.title = feature.description;
            $('feature').append(option);
        });
        $('feature').value = selectedFeature;
        for (const key of ['usersWithRecords', 'usersWithActions', 'actionRecords', 'featuresUsed']) {
            $(key).textContent = number.format(result.summary[key]);
        }
        $('activity-rows').replaceChildren(...result.items.map(renderUser));
        $('empty').hidden = result.items.length !== 0;
        $('resultDate').textContent = `${result.date} 사용자 활동`;
        $('updatedAt').textContent = `KST 하루 기준 · ${formatTime(result.generatedAt)} 집계 · 집계 결과를 15초간 재사용 · 요약은 조회 조건에 맞는 전체 사용자 기준`;
        const pages = Math.max(1, Math.ceil(result.totalElements / result.size));
        $('pageInfo').textContent = `${page + 1} / ${number.format(pages)}페이지 · ${number.format(result.totalElements)}명`;
    }

    async function load() {
        const current = ++requestId;
        if (activeRequest) activeRequest.abort();
        activeRequest = new AbortController();
        $('error').hidden = true;
        $('results').setAttribute('aria-busy', 'true');
        $('loadStatus').textContent = data ? '새 결과 조회 중… 아래는 이전 조회 결과입니다.' : '불러오는 중…';
        $('previous').disabled = true;
        $('next').disabled = true;
        const params = new URLSearchParams({ page: String(page), size: '30' });
        for (const name of ['date', 'query', 'feature']) {
            if ($(name).value.trim()) params.set(name, $(name).value.trim());
        }
        params.set('actionsOnly', String($('actionsOnly').checked));
        try {
            const response = await fetch(`/admin/user-activities?${params}`, {
                credentials: 'same-origin', headers: { Accept: 'application/json' }, signal: activeRequest.signal,
            });
            if (response.redirected && new URL(response.url).pathname === '/login') {
                window.location.assign('/login');
                return;
            }
            if (!response.ok) {
                const message = response.status === 503 ? '다른 활동 집계를 처리 중입니다. 잠시 후 다시 조회해 주세요.'
                    : response.status === 403 ? '이 화면을 조회할 관리자 권한이 없습니다.'
                    : '활동 기록을 불러오지 못했습니다. 날짜를 확인한 뒤 다시 시도해 주세요.';
                throw new Error(message);
            }
            const result = await response.json();
            if (current !== requestId) return;
            render(result);
            $('loadStatus').textContent = '조회 완료';
        } catch (error) {
            if (error.name === 'AbortError' || current !== requestId) return;
            $('error').textContent = error.message;
            $('error').hidden = false;
            $('loadStatus').textContent = data ? '조회 실패 · 아래는 이전 조회 결과입니다.' : '조회 실패';
        } finally {
            if (current === requestId) {
                $('results').setAttribute('aria-busy', 'false');
                $('previous').disabled = !data || data.page === 0;
                $('next').disabled = !data || (data.page + 1) * data.size >= data.totalElements;
            }
        }
    }
    $('activity-filter').addEventListener('submit', (event) => { event.preventDefault(); page = 0; void load(); });
    $('refresh').addEventListener('click', () => void load());
    $('today').addEventListener('click', () => { $('date').value = ''; page = 0; void load(); });
    $('previous').addEventListener('click', () => { page = Math.max(0, page - 1); void load(); });
    $('next').addEventListener('click', () => { page += 1; void load(); });
    void load();
})();
