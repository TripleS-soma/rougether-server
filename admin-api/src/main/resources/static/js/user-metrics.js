(() => {
    'use strict';

    const byId = id => document.getElementById(id);
    const SEGMENTS = { PERSONAL: '개인군', SHARED: '공동군', OVERALL: '전체' };
    const SEGMENT_COLORS = { PERSONAL: '#6366f1', SHARED: '#0d9488', OVERALL: '#64748b' };
    const RETENTION_SERIES = [
        { key: 'd1', label: 'D1', color: '#6366f1', symbol: '●' },
        { key: 'd7', label: 'D7', color: '#0d9488', symbol: '■' },
        { key: 'd30', label: 'D30', color: '#a16207', symbol: '◆' }
    ];
    const CHART_IDS = ['retentionComparison', 'northStarChart', 'signupChart', 'retentionTrendChart',
        'completionSegmentChart', 'restartSegmentChart', 'streakSegmentChart'];
    const SUMMARY_IDS = ['activeUsers', 'newUsersLast7Days', 'recentlyAccessedLast7Days',
        'onboardingCompletionRate'];
    const KST_DATE_TIME = new Intl.DateTimeFormat('ko-KR', {
        timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit',
        hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false
    });
    let summaryRequest = 0;
    let metricsRequest = 0;
    let metricsAbort;
    let currentCohorts;
    let chartWidths = [];

    function element(tag, className, text) {
        const node = document.createElement(tag);
        if (className) node.className = className;
        if (text !== undefined) node.textContent = text;
        return node;
    }

    function svgElement(tag, attributes = {}, text) {
        const node = document.createElementNS('http://www.w3.org/2000/svg', tag);
        Object.entries(attributes).forEach(([key, value]) => node.setAttribute(key, value));
        if (text !== undefined) node.textContent = text;
        return node;
    }

    function number(value) {
        return Number.isFinite(value) ? value.toLocaleString('ko-KR') : '—';
    }

    function rate(value, denominator) {
        if (!Number.isFinite(denominator) || denominator <= 0) return '대상 없음';
        return Number.isFinite(value) ? value.toFixed(1) + '%' : '집계 없음';
    }

    function periodText(period) {
        if (!period || period.days <= 0 || period.fromDate > period.toDate) return '확정된 관측일 없음';
        return period.fromDate + ' ~ ' + period.toDate + ' (KST)';
    }

    function generatedAtText(value) {
        const date = new Date(value);
        return value && Number.isFinite(date.getTime()) ? KST_DATE_TIME.format(date) + ' KST' : '시각 정보 없음';
    }

    async function readJson(url, signal) {
        const response = await fetch(url, { signal, headers: { Accept: 'application/json' } });
        if (!response.ok) throw new Error('HTTP ' + response.status);
        if (response.redirected && !response.headers.get('content-type')?.includes('application/json')) {
            throw new Error('로그인 상태를 확인해 주세요.');
        }
        return response.json();
    }

    function setStatus(id, message, failed = false) {
        const node = byId(id);
        node.textContent = message;
        node.hidden = !message;
        node.classList.toggle('is-error', failed);
    }

    async function loadSummary() {
        const request = ++summaryRequest;
        SUMMARY_IDS.forEach(id => { byId(id).textContent = '—'; });
        byId('onboardingCompletedUsers').textContent = '목표 + 대표 캐릭터';
        byId('summaryGeneratedAt').textContent = '요약 조회 중…';
        setStatus('summaryStatus', '회원 요약을 불러오는 중입니다.');
        try {
            const summary = await readJson('/admin/users/summary');
            if (request !== summaryRequest) return;
            byId('activeUsers').textContent = number(summary.activeUsers);
            byId('newUsersLast7Days').textContent = number(summary.newUsersLast7Days);
            byId('recentlyAccessedLast7Days').textContent = number(summary.recentlyAccessedLast7Days);
            byId('onboardingCompletionRate').textContent = rate(summary.onboardingCompletionRate, summary.activeUsers);
            byId('onboardingCompletedUsers').textContent = number(summary.onboardingCompletedUsers) +
                ' / ' + number(summary.activeUsers) + '명 완료';
            byId('summaryGeneratedAt').textContent = '회원 요약 조회 · ' + generatedAtText(summary.generatedAt);
            setStatus('summaryStatus', '');
        } catch (error) {
            if (request !== summaryRequest) return;
            byId('summaryGeneratedAt').textContent = '회원 요약 조회 실패';
            setStatus('summaryStatus', '회원 요약을 불러오지 못했습니다. ' + error.message +
                ' · 상단 새로고침으로 다시 조회할 수 있습니다.', true);
        }
    }

    function chartState(message) {
        CHART_IDS.forEach(id => byId(id).replaceChildren(element('div', 'metrics-panel-state', message)));
        byId('segmentMembers').replaceChildren();
        byId('completionPeriod').textContent = '확정 범위 조회 중';
        byId('restartPeriod').textContent = '최근 30일 기준';
        byId('streakPeriod').textContent = '현재 스트릭 기준';
        byId('northStarPeriod').textContent = '최근 7개 KST 일자';
        byId('completionFinalizedNote').textContent = '완료율 확정 범위 조회 중…';
        ['retentionSegmentRows', 'retentionCohortRows'].forEach(id => {
            const row = element('tr');
            const cell = element('td', 'observation-empty', message);
            cell.colSpan = 5;
            row.append(cell);
            byId(id).replaceChildren(row);
        });
    }

    async function loadMetrics() {
        const request = ++metricsRequest;
        if (metricsAbort) metricsAbort.abort();
        metricsAbort = new AbortController();
        const cohortDays = byId('cohortDays').value;
        currentCohorts = null;
        byId('metricsDashboard').setAttribute('aria-busy', 'true');
        byId('retentionAsOf').textContent = 'KST 기준일 조회 중';
        byId('metricsGeneratedAt').textContent = '지표 조회 중…';
        chartState('지표를 불러오는 중입니다.');
        setStatus('metricsStatus', '가입 코호트 ' + cohortDays + '일의 지표를 불러오는 중입니다.');
        try {
            const metrics = await readJson('/admin/retention/metrics?cohortDays=' + cohortDays, metricsAbort.signal);
            if (request !== metricsRequest) return;
            renderMetrics(metrics);
            setStatus('metricsStatus', '');
        } catch (error) {
            if (request !== metricsRequest || error.name === 'AbortError') return;
            chartState('조회 실패 · 상단 새로고침으로 다시 조회해 주세요.');
            byId('completionFinalizedNote').textContent = '완료율 확정 범위를 확인하지 못했습니다.';
            byId('completionPeriod').textContent = '조회 실패';
            byId('retentionAsOf').textContent = 'KST 기준일 조회 실패';
            byId('metricsGeneratedAt').textContent = '지표 조회 실패';
            setStatus('metricsStatus', '지표를 불러오지 못했습니다. ' + error.message, true);
        } finally {
            if (request === metricsRequest) byId('metricsDashboard').setAttribute('aria-busy', 'false');
        }
    }

    function renderMetrics(metrics) {
        byId('retentionAsOf').textContent = 'KST ' + metrics.asOfDate + ' 기준 · ' +
            metrics.cohortPeriod.fromDate + ' ~ ' + metrics.cohortPeriod.toDate;
        byId('metricsGeneratedAt').textContent = '행동 지표 조회 · ' + generatedAtText(metrics.generatedAt);
        byId('completionFinalizedNote').textContent = metrics.dayEndFinalizedThroughDate
            ? '완료율은 확정일 ' + metrics.dayEndFinalizedThroughDate + '까지만 집계합니다.'
            : '완료율 확정 정보가 없어 미확정 날짜가 포함될 수 있습니다.';
        renderRetention(metrics.retention);
        renderNorthStar(metrics.northStar);
        const days = dailyCohorts(metrics.cohortPeriod, metrics.retentionCohorts);
        currentCohorts = days;
        renderSignupChart(days);
        renderRetentionChart(days);
        chartWidths = ['signupChart', 'retentionTrendChart'].map(id => byId(id).clientWidth);
        renderSegments(metrics.segments);
        renderDetails(metrics.segments, metrics.retentionCohorts);
    }

    function addBar(container, { label, value, maximum = 100, unit = '%', denominator, meta, color }) {
        const row = element('div', 'metrics-bar-row');
        const head = element('div', 'metrics-bar-label');
        const available = denominator > 0 && Number.isFinite(value);
        head.append(element('span', '', label), element('strong', available ? '' : 'is-empty',
            available ? value.toFixed(1) + unit : '대상 없음'));
        const track = element('div', 'metrics-track' + (available ? '' : ' is-empty'));
        if (available) {
            track.setAttribute('role', 'meter');
            track.setAttribute('aria-label', label);
            track.setAttribute('aria-valuemin', '0');
            track.setAttribute('aria-valuemax', maximum);
            track.setAttribute('aria-valuenow', value);
            track.setAttribute('aria-valuetext', value.toFixed(1) + unit + ' · ' + meta);
            const fill = element('div', 'metrics-fill');
            fill.style.width = Math.max(0, Math.min(100, value / maximum * 100)) + '%';
            fill.style.backgroundColor = color;
            track.append(fill);
        }
        row.append(head, track, element('div', 'metrics-bar-meta', meta));
        container.append(row);
    }

    function addAxis(container, maximum = 100, unit = '%') {
        const axis = element('div', 'metrics-axis');
        axis.setAttribute('aria-hidden', 'true');
        [0, .25, .5, .75, 1].forEach(part => axis.append(element('span', '',
            number(maximum * part) + unit)));
        container.append(axis);
    }

    function renderRetention(retention) {
        const panel = element('div', 'metrics-comparison');
        RETENTION_SERIES.forEach(series => {
            const metric = retention[series.key];
            addBar(panel, {
                label: series.label + ' 재방문', value: metric.percentage,
                denominator: metric.eligibleUserCount, color: series.color,
                meta: number(metric.returnedUserCount) + ' / ' + number(metric.eligibleUserCount) +
                    '명 · ' + metric.eligibleSignupThroughDate + '까지 가입한 도달자'
            });
        });
        addAxis(panel);
        byId('retentionComparison').replaceChildren(panel);
    }

    function renderNorthStar(metric) {
        const container = element('div');
        const value = element('div', 'metrics-north-value');
        value.append(element('strong', '', rate(metric.percentage, metric.registeredUserCount)),
            element('span', '', '주 3일 이상 루틴 완료'));
        const formula = element('div', 'metrics-north-formula');
        formula.append(element('strong', '', number(metric.qualifiedUserCount) + '명'),
            document.createTextNode(' / 현재 가입자 ' + number(metric.registeredUserCount) + '명'));
        container.append(value, formula);
        const track = element('div', 'metrics-track' + (metric.registeredUserCount > 0 ? '' : ' is-empty'));
        if (metric.registeredUserCount > 0) {
            track.setAttribute('role', 'meter');
            track.setAttribute('aria-label', '주 3일 루틴 완료자 비율');
            track.setAttribute('aria-valuemin', '0');
            track.setAttribute('aria-valuemax', '100');
            track.setAttribute('aria-valuenow', metric.percentage);
            track.setAttribute('aria-valuetext', rate(metric.percentage, metric.registeredUserCount) +
                ', ' + number(metric.qualifiedUserCount) + ' / ' + number(metric.registeredUserCount) + '명');
            const fill = element('div', 'metrics-fill');
            fill.style.width = Math.max(0, Math.min(100, metric.percentage)) + '%';
            fill.style.backgroundColor = '#0d9488';
            track.append(fill);
        }
        container.append(track);
        addAxis(container);
        byId('northStarPeriod').textContent = periodText(metric.period);
        byId('northStarChart').replaceChildren(container);
    }

    function dailyCohorts(period, cohorts) {
        const mapped = new Map(cohorts.map(cohort => [cohort.cohortDate, cohort]));
        const from = Date.parse(period.fromDate + 'T00:00:00Z');
        const to = Date.parse(period.toDate + 'T00:00:00Z');
        const days = [];
        for (let date = from; date <= to && days.length < 90; date += 86400000) {
            const key = new Date(date).toISOString().slice(0, 10);
            days.push(mapped.get(key) ?? { cohortDate: key, cohortUserCount: 0, absent: true });
        }
        return days;
    }

    function chartBase(days, maximum, percentage, label, host) {
        const width = Math.max(220, Math.min(700, host.clientWidth || 700)), height = 250;
        const left = 42, right = 14, top = 18, bottom = 36;
        const plotWidth = width - left - right, plotHeight = height - top - bottom;
        const svg = svgElement('svg', { viewBox: '0 0 ' + width + ' ' + height,
            role: 'group', 'aria-label': label });
        const y = value => top + plotHeight * (1 - value / maximum);
        const x = index => left + plotWidth * (index + .5) / days.length;
        const ticks = percentage ? [0, 25, 50, 75, 100]
            : Array.from(new Set([0, Math.floor(maximum / 2), maximum]));
        ticks.forEach(tick => {
            svg.append(svgElement('line', { x1: left, x2: width - right, y1: y(tick), y2: y(tick),
                class: 'metrics-grid-line' }), svgElement('text', { x: left - 9, y: y(tick) + 4,
                'text-anchor': 'end' }, number(tick) + (percentage ? '%' : '')));
        });
        const labelCount = Math.max(2, Math.floor(plotWidth / 70));
        const interval = Math.max(1, Math.ceil(days.length / labelCount));
        days.forEach((day, index) => {
            if (index % interval === 0 || index === days.length - 1) {
                if (index !== days.length - 1 && days.length - 1 - index < interval / 2) return;
                svg.append(svgElement('text', { x: x(index), y: height - 10,
                    'text-anchor': 'middle' }, day.cohortDate.slice(5).replace('-', '/')));
            }
        });
        return { svg, x, y, top, plotHeight, barWidth: plotWidth / days.length };
    }

    function interactivePoints(points, readout) {
        points.forEach((point, index) => {
            point.node.setAttribute('tabindex', index === 0 ? '0' : '-1');
            point.node.setAttribute('aria-label', point.text);
            point.node.append(svgElement('title', {}, point.text));
            const show = () => { readout.textContent = point.text; };
            point.node.addEventListener('pointerenter', show);
            point.node.addEventListener('click', show);
            point.node.addEventListener('focus', show);
            point.node.addEventListener('keydown', event => {
                let next;
                if (event.key === 'ArrowRight') next = Math.min(index + 1, points.length - 1);
                if (event.key === 'ArrowLeft') next = Math.max(index - 1, 0);
                if (event.key === 'Home') next = 0;
                if (event.key === 'End') next = points.length - 1;
                if (next === undefined) return;
                event.preventDefault();
                points.forEach(item => item.node.setAttribute('tabindex', '-1'));
                points[next].node.setAttribute('tabindex', '0');
                points[next].node.focus();
            });
        });
    }

    function chartReadout(container, svg, text) {
        const wrap = element('div', 'metrics-chart');
        const readout = element('div', 'metrics-readout', text);
        readout.setAttribute('aria-live', 'polite');
        const hint = element('div', 'metrics-chart-hint',
            '막대·점을 가리키거나 선택하면 수치를 볼 수 있습니다. 키보드: Tab 진입, ← → 이동.');
        wrap.append(svg, hint, readout);
        container.replaceChildren(wrap);
        return readout;
    }

    function renderSignupChart(days) {
        if (!days.length) {
            byId('signupChart').replaceChildren(element('div', 'metrics-panel-state', '조회 기간이 없습니다.'));
            return;
        }
        const largest = Math.max(1, ...days.map(day => day.cohortUserCount));
        const step = Math.pow(10, Math.floor(Math.log10(largest)));
        const maximum = Math.ceil(largest / step) * step;
        const chart = chartBase(days, maximum, false, '가입일별 현재 회원수 막대 그래프, 단위 명', byId('signupChart'));
        const points = days.map((day, index) => {
            const group = svgElement('g', { class: 'metrics-chart-point' });
            const height = chart.y(0) - chart.y(day.cohortUserCount);
            group.append(svgElement('rect', { x: chart.x(index) - chart.barWidth * .34,
                y: chart.y(day.cohortUserCount), width: chart.barWidth * .68, height,
                rx: 2, fill: '#6366f1' }));
            group.append(svgElement('rect', { x: chart.x(index) - chart.barWidth * .45,
                y: chart.top, width: chart.barWidth * .9, height: chart.plotHeight,
                fill: 'transparent', class: 'metrics-hit' }));
            chart.svg.append(group);
            return { node: group, text: '가입일 ' + day.cohortDate + ' (KST) · 현재 회원 ' +
                number(day.cohortUserCount) + '명 · 탈퇴·봇 제외' };
        });
        const empty = days.every(day => day.cohortUserCount === 0);
        const readout = chartReadout(byId('signupChart'), chart.svg, empty
            ? '이 가입 코호트 기간에 현재 회원이 없습니다. 일별 회원수는 0명입니다.'
            : '가입일별 현재 회원수 · 탈퇴·봇 제외');
        interactivePoints(points, readout);
    }

    function retentionPointText(point, absent = false) {
        if (absent) return '가입자 없음';
        if (!point) return '미도달';
        return rate(point.percentage, point.eligibleUserCount) + ' (' +
            number(point.returnedUserCount) + ' / ' + number(point.eligibleUserCount) + '명)';
    }

    function renderRetentionChart(days) {
        if (!days.length) {
            byId('retentionTrendChart').replaceChildren(element('div', 'metrics-panel-state', '조회 기간이 없습니다.'));
            return;
        }
        const chart = chartBase(days, 100, true, '가입 코호트별 D1 D7 D30 재방문율, 0부터 100퍼센트', byId('retentionTrendChart'));
        const seriesPoints = [];
        RETENTION_SERIES.forEach((series, seriesIndex) => {
            let path = '', previous = false;
            const points = [];
            days.forEach((day, index) => {
                const point = day[series.key];
                if (!point || point.eligibleUserCount <= 0 || !Number.isFinite(point.percentage)) {
                    previous = false;
                    return;
                }
                const x = chart.x(index), y = chart.y(point.percentage);
                path += (previous ? ' L ' : ' M ') + x + ' ' + y;
                previous = true;
                const group = svgElement('g', { class: 'metrics-chart-point' });
                const common = { fill: series.color, stroke: 'white', 'stroke-width': 1.4 };
                if (series.key === 'd1') group.append(svgElement('circle', { ...common, cx: x, cy: y, r: 4.2 }));
                if (series.key === 'd7') group.append(svgElement('rect', { ...common, x: x - 4, y: y - 4, width: 8, height: 8 }));
                if (series.key === 'd30') group.append(svgElement('path', { ...common,
                    d: 'M ' + x + ' ' + (y - 5) + ' l 5 5 -5 5 -5 -5 Z' }));
                group.append(svgElement('circle', { cx: x, cy: y, r: 8, fill: 'transparent', class: 'metrics-hit' }));
                points.push({ node: group, text: '가입일 ' + day.cohortDate + ' (KST) · ' +
                    series.label + ' 재방문 ' + retentionPointText(point) });
            });
            chart.svg.append(svgElement('path', { d: path, fill: 'none', stroke: series.color,
                'stroke-width': 2, 'stroke-dasharray': seriesIndex === 1 ? '6 4' : seriesIndex === 2 ? '2 4' : '' }));
            seriesPoints.push(points);
        });
        seriesPoints.flat().forEach(point => chart.svg.append(point.node));
        const hasPoints = seriesPoints.some(points => points.length > 0);
        const readout = chartReadout(byId('retentionTrendChart'), chart.svg, hasPoints
            ? '가입일별 도달자의 재방문 비율 · 미도달·가입자 없는 날짜는 선이 끊깁니다.'
            : '관측 가능한 재방문 지표가 없습니다. 미도달 코호트는 집계 상세에서 확인할 수 있습니다.');
        seriesPoints.forEach(points => interactivePoints(points, readout));
    }

    function renderSegments(segments) {
        const ordered = ['PERSONAL', 'SHARED', 'OVERALL'].map(key => segments.find(item => item.segment === key)).filter(Boolean);
        byId('segmentMembers').replaceChildren(...ordered.map(item =>
            element('span', '', SEGMENTS[item.segment] + ' ' + number(item.userCount) + '명')));
        const streakMaximum = Math.max(1, Math.ceil(Math.max(0, ...ordered.map(item => item.currentStreak.average))));
        const configs = [
            { id: 'completionSegmentChart', key: 'completionRate', numerator: 'completedLogCount',
                denominator: 'decidedLogCount', unit: '%', maximum: 100 },
            { id: 'restartSegmentChart', key: 'restartRate', numerator: 'restartedUserCount',
                denominator: 'gapExperiencedUserCount', unit: '%', maximum: 100 },
            { id: 'streakSegmentChart', key: 'currentStreak', numerator: 'currentStreakSum',
                denominator: 'userCount', unit: '일', maximum: streakMaximum }
        ];
        configs.forEach(config => {
            const container = element('div', 'metrics-comparison');
            ordered.forEach(item => {
                const metric = item[config.key];
                addBar(container, { label: SEGMENTS[item.segment],
                    value: config.key === 'currentStreak' ? metric.average : metric.percentage,
                    denominator: metric[config.denominator], maximum: config.maximum,
                    unit: config.unit, color: SEGMENT_COLORS[item.segment],
                    meta: number(metric[config.numerator]) + (config.unit === '일' ? '일 합계' : '') +
                        ' / ' + number(metric[config.denominator]) + (config.key === 'completionRate' ? '건' : '명')
                });
            });
            addAxis(container, config.maximum, config.unit);
            byId(config.id).replaceChildren(ordered.length ? container
                : element('div', 'metrics-panel-state', '세그먼트 집계가 없습니다.'));
        });
        const reference = ordered.find(item => item.segment === 'OVERALL') ?? ordered[0];
        byId('completionPeriod').textContent = reference ? periodText(reference.completionRate.period) : '집계 없음';
        byId('restartPeriod').textContent = reference ? periodText(reference.restartRate.period) : '집계 없음';
        byId('streakPeriod').textContent = reference ? 'KST ' + reference.currentStreak.asOfDate + ' 현재' : '집계 없음';
    }

    function tableRow(values) {
        const row = element('tr');
        values.forEach(value => row.append(element('td', '', value)));
        return row;
    }

    function rateFormula(metric, numerator, denominator) {
        return rate(metric.percentage, denominator) + ' (' + number(numerator) + '/' +
            number(denominator) + ') · ' + periodText(metric.period);
    }

    function renderDetails(segments, cohorts) {
        byId('retentionSegmentRows').replaceChildren(...segments.map(metric => tableRow([
            SEGMENTS[metric.segment] ?? metric.segment, number(metric.userCount),
            rateFormula(metric.completionRate, metric.completionRate.completedLogCount, metric.completionRate.decidedLogCount),
            (metric.currentStreak.userCount > 0 ? metric.currentStreak.average.toFixed(1) + '일' : '대상 없음') +
                ' (' + number(metric.currentStreak.currentStreakSum) + '일 / ' + number(metric.currentStreak.userCount) + '명)',
            rateFormula(metric.restartRate, metric.restartRate.restartedUserCount, metric.restartRate.gapExperiencedUserCount)
        ])));
        if (!cohorts.length) {
            const row = tableRow(['조회 기간에 현재 회원이 있는 가입 코호트가 없습니다.']);
            row.firstChild.colSpan = 5;
            row.firstChild.className = 'observation-empty';
            byId('retentionCohortRows').replaceChildren(row);
        } else {
            byId('retentionCohortRows').replaceChildren(...cohorts.map(cohort => tableRow([
                cohort.cohortDate, number(cohort.cohortUserCount), retentionPointText(cohort.d1),
                retentionPointText(cohort.d7), retentionPointText(cohort.d30)
            ])));
        }
    }

    byId('cohortDays').addEventListener('change', loadMetrics);
    function resizeCharts() {
        if (!currentCohorts) return;
        const widths = ['signupChart', 'retentionTrendChart'].map(id => byId(id).clientWidth);
        if (widths.every((width, index) => width === chartWidths[index])) return;
        chartWidths = widths;
        renderSignupChart(currentCohorts);
        renderRetentionChart(currentCohorts);
    }
    if (typeof ResizeObserver !== 'undefined') {
        const observer = new ResizeObserver(resizeCharts);
        ['signupChart', 'retentionTrendChart'].forEach(id => observer.observe(byId(id)));
    } else {
        window.addEventListener('resize', resizeCharts);
    }
    window.AdminUserMetrics = { refresh: () => Promise.all([loadSummary(), loadMetrics()]) };
})();
