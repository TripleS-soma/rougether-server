(() => {
    'use strict';

    const STATUS_LABELS = {
        DRAFT: '아이디어', BUILT: '빌드 완료', VALIDATED: '자동 QA 통과',
        REVIEW_CANDIDATE: '사람 검수', APPROVED: '승인', UPLOADED: 'S3 업로드',
        LINKED_DEV: 'DEV 연결', SEED_SYNCED: 'Seed 동기화', CLIENT_VERIFIED: '모바일 검증'
    };
    const KIND_LABELS = {
        STATIC_FURNITURE: '정적 가구', ANIMATED_FURNITURE: '동적 가구',
        CHARACTER_ANIMATION: '캐릭터 모션', HOUSE_FRAME: '집 프레임'
    };
    const BOARD_GROUPS = [
        {id: 'idea', label: 'IDEA', hint: 'brief & manifest', statuses: ['DRAFT']},
        {id: 'build', label: 'BUILD', hint: 'deterministic output', statuses: ['BUILT']},
        {id: 'quality', label: 'QUALITY', hint: 'automated gates', statuses: ['VALIDATED']},
        {id: 'review', label: 'REVIEW', hint: 'human in the loop', statuses: ['REVIEW_CANDIDATE']},
        {id: 'delivery', label: 'DELIVERY', hint: 'S3 · DB · seed', statuses: ['APPROVED', 'UPLOADED', 'LINKED_DEV', 'SEED_SYNCED']},
        {id: 'live', label: 'LIVE', hint: 'iOS · Android', statuses: ['CLIENT_VERIFIED']}
    ];

    const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;
    const s3BaseUrl = (window.ASSET_FOUNDRY_CONFIG?.s3BaseUrl || '').replace(/\/$/, '');
    const board = document.getElementById('foundry-board');
    const errorBox = document.getElementById('foundry-error');
    const successBox = document.getElementById('foundry-success');
    const detailEmpty = document.getElementById('foundry-empty-detail');
    const detailContent = document.getElementById('foundry-detail-content');
    const newJobDialog = document.getElementById('new-job-dialog');
    const qaReportDialog = document.getElementById('qa-report-dialog');

    let jobs = [];
    let selectedJobId = null;
    let roomContract = null;

    init();

    async function init() {
        bindEvents();
        try {
            [roomContract] = await Promise.all([
                getJson('/contracts/room-render-contract.v1.json'),
                loadJobs()
            ]);
            applyRoomContract();
            renderAll();
        } catch (error) {
            showError(error.message);
        }
    }

    function bindEvents() {
        bindPreviewFallbacks();
        document.getElementById('new-job-button').addEventListener('click', () => newJobDialog.showModal());
        document.getElementById('refresh-jobs').addEventListener('click', refresh);
        document.getElementById('new-job-form').addEventListener('submit', createJob);
        document.getElementById('qa-report-button').addEventListener('click', openQaDialog);
        document.getElementById('qa-report-form').addEventListener('submit', submitQaReport);
        document.getElementById('advance-button').addEventListener('click', advanceSelected);
        document.getElementById('return-draft-button').addEventListener('click', returnSelectedToDraft);
        document.getElementById('new-job-kind').addEventListener('change', updateKindHint);
        document.querySelectorAll('[data-dialog-close]').forEach((button) => {
            button.addEventListener('click', () => document.getElementById(button.dataset.dialogClose).close());
        });
    }

    async function refresh() {
        try {
            await loadJobs();
            renderAll();
            showSuccess('파이프라인 현황을 새로 불러왔습니다.');
        } catch (error) {
            showError(error.message);
        }
    }

    async function loadJobs() {
        const response = await getJson('/admin/asset-foundry/jobs');
        jobs = response.items || [];
        if (selectedJobId && !jobs.some((job) => job.id === selectedJobId)) selectedJobId = null;
        if (!selectedJobId && jobs.length > 0) selectedJobId = jobs[0].id;
    }

    function renderAll() {
        renderMetrics();
        renderBoard();
        renderDetail();
    }

    function renderMetrics() {
        const total = jobs.length;
        const live = jobs.filter((job) => job.status === 'CLIENT_VERIFIED').length;
        const review = jobs.filter((job) => job.status === 'REVIEW_CANDIDATE').length;
        const active = total - live;
        const scored = jobs.filter((job) => Number.isFinite(job.qualityScore));
        const average = scored.length
            ? Math.round(scored.reduce((sum, job) => sum + job.qualityScore, 0) / scored.length)
            : null;
        setText('metric-total', total);
        setText('metric-active', active);
        setText('metric-review', review);
        setText('metric-live', live);
        setText('metric-quality', average === null ? '—' : average + '%');
    }

    function renderBoard() {
        board.replaceChildren(...BOARD_GROUPS.map((group) => {
            const column = document.createElement('section');
            column.className = 'foundry-column foundry-column--' + group.id;
            const matching = jobs.filter((job) => group.statuses.includes(job.status));
            column.innerHTML = `
                <div class="foundry-column-head">
                    <div><strong>${group.label}</strong><span>${group.hint}</span></div>
                    <b>${matching.length}</b>
                </div>
                <div class="foundry-column-cards"></div>`;
            const cards = column.querySelector('.foundry-column-cards');
            if (matching.length === 0) {
                const empty = document.createElement('div');
                empty.className = 'foundry-column-empty';
                empty.textContent = '대기 작업 없음';
                cards.appendChild(empty);
            } else {
                cards.append(...matching.map(buildJobCard));
            }
            return column;
        }));
    }

    function buildJobCard(job) {
        const card = document.createElement('button');
        card.type = 'button';
        card.className = 'foundry-job-card' + (job.id === selectedJobId ? ' selected' : '');
        card.dataset.jobId = job.id;
        const score = Number.isFinite(job.qualityScore) ? job.qualityScore + '%' : 'QA —';
        card.innerHTML = `
            <span class="foundry-card-kind">${escapeHtml(KIND_LABELS[job.kind] || job.kind)}</span>
            <strong>${escapeHtml(job.name)}</strong>
            <small>${escapeHtml(job.themeCode || 'theme-free')}</small>
            <div class="foundry-card-foot">
                <span>${escapeHtml(STATUS_LABELS[job.status] || job.status)}</span>
                <b class="${job.qaPassed ? 'passed' : ''}">${score}</b>
            </div>`;
        card.addEventListener('click', () => {
            selectedJobId = job.id;
            renderBoard();
            renderDetail();
        });
        return card;
    }

    function renderDetail() {
        const job = selectedJob();
        detailEmpty.hidden = Boolean(job);
        detailContent.hidden = !job;
        if (!job) return;

        setText('detail-kind', KIND_LABELS[job.kind] || job.kind);
        setText('detail-name', job.name);
        setText('detail-slug', job.slug);
        setText('detail-status', STATUS_LABELS[job.status] || job.status);
        document.getElementById('detail-status').dataset.status = job.status;
        setText('detail-score', Number.isFinite(job.qualityScore) ? job.qualityScore : '—');
        setText('detail-output-key', job.outputAssetKey || '—');
        setText('detail-target', job.targetItemId ? 'item #' + job.targetItemId : '신규 에셋');
        setText('detail-old-key', job.expectedOldAssetKey || '해당 없음');
        setText('detail-approver', job.approvedBy || '승인 대기');
        setText('detail-preview-key', job.previewAssetKey || job.outputAssetKey || '미리보기 key 없음');
        const qaReportButton = document.getElementById('qa-report-button');
        qaReportButton.disabled = !['BUILT', 'VALIDATED'].includes(job.status);
        qaReportButton.title = qaReportButton.disabled ? '빌드 완료 이후 QA 리포트를 반영할 수 있습니다.' : '';
        renderChecks(job);
        renderEvents(job);
        renderPreview(job);
        renderTransition(job);
    }

    function renderChecks(job) {
        const list = document.getElementById('detail-checks');
        if (!job.checks?.length) {
            list.innerHTML = '<div class="foundry-check-empty">아직 QA 리포트가 없습니다. 빌드 후 <code>assetctl validate</code> 결과를 반영하세요.</div>';
            return;
        }
        list.replaceChildren(...job.checks.map((check) => {
            const item = document.createElement('div');
            item.className = 'foundry-check foundry-check--' + check.result.toLowerCase();
            item.innerHTML = `
                <span class="foundry-check-mark">${check.result === 'PASS' ? '✓' : check.result === 'FAIL' ? '!' : '·'}</span>
                <div><strong>${escapeHtml(check.label)}</strong><small>${escapeHtml(check.message || check.code)}</small></div>
                <b>${check.score === null ? check.result : check.score}</b>`;
            return item;
        }));
    }

    function renderEvents(job) {
        const timeline = document.getElementById('detail-events');
        if (!job.events?.length) {
            timeline.innerHTML = '<p>이력 없음</p>';
            return;
        }
        timeline.replaceChildren(...job.events.map((event) => {
            const row = document.createElement('div');
            row.innerHTML = `
                <i></i>
                <div><strong>${escapeHtml(event.note)}</strong>
                <span>${escapeHtml(event.actor)} · ${formatDate(event.createdAt)}</span></div>`;
            return row;
        }));
    }

    function applyRoomContract() {
        if (!roomContract) return;
        const stage = document.getElementById('foundry-room-stage');
        stage.style.aspectRatio = roomContract.room.aspectRatio;
        stage.style.borderRadius = roomContract.room.borderRadiusPx + 'px';
        setRect('foundry-room-background', roomContract.surfaces.background);
        setRect('foundry-room-wall', roomContract.surfaces.wallpaper);
        setRect('foundry-room-floor', roomContract.surfaces.floor);
        const fixture = roomContract.referenceFixture;
        setSrc('foundry-room-background', fixture.surfaces.background.assetKey);
        setSrc('foundry-room-wall', fixture.surfaces.wallpaper.assetKey);
        setSrc('foundry-room-floor', fixture.surfaces.floor.assetKey);
        setSrc('foundry-room-character', fixture.character.animations.idle);
        const character = roomContract.character;
        const characterImage = document.getElementById('foundry-room-character');
        characterImage.style.left = percent(character.centerX - character.width / 2);
        characterImage.style.bottom = percent(character.bottom);
        characterImage.style.width = percent(character.width);
        characterImage.style.height = percent(character.height);
        setText('contract-version', 'Room renderer contract v' + roomContract.version);
    }

    function renderPreview(job) {
        if (!roomContract) return;
        const preview = document.getElementById('foundry-preview-asset');
        const small = document.getElementById('foundry-small-asset');
        const character = document.getElementById('foundry-room-character');
        const key = job.previewAssetKey || job.outputAssetKey;
        document.getElementById('foundry-preview-unavailable').hidden = true;
        preview.className = 'foundry-preview-asset';
        preview.hidden = false;
        small.hidden = false;
        character.style.visibility = 'visible';
        setSrc('foundry-room-character', roomContract.referenceFixture.character.animations.idle);
        if (!key) {
            preview.removeAttribute('src');
            small.removeAttribute('src');
            return;
        }
        preview.src = assetUrl(key);
        small.src = assetUrl(key);
        preview.alt = job.name;
        small.alt = job.name + ' 86px';
        if (job.kind === 'HOUSE_FRAME') {
            preview.classList.add('foundry-preview-asset--house');
            character.style.visibility = 'hidden';
        } else if (job.kind === 'CHARACTER_ANIMATION') {
            preview.classList.add('foundry-preview-asset--character');
            character.style.visibility = 'hidden';
        } else {
            const center = roomContract.furniture.previewCenter;
            preview.style.left = percent(center.x);
            preview.style.top = percent(center.y);
            preview.style.width = percent(roomContract.furniture.baseWidth);
        }
    }

    function renderTransition(job) {
        const advance = document.getElementById('advance-button');
        const returnDraft = document.getElementById('return-draft-button');
        returnDraft.hidden = job.status !== 'REVIEW_CANDIDATE';
        if (!job.nextStatus) {
            advance.disabled = true;
            advance.textContent = '모바일 검증 완료';
        } else {
            advance.disabled = job.status === 'BUILT' && !job.qaPassed;
            const isExternalAttestation = ['APPROVED', 'UPLOADED', 'LINKED_DEV', 'SEED_SYNCED']
                .includes(job.status);
            advance.textContent = job.status === 'REVIEW_CANDIDATE'
                ? '승인하고 ' + STATUS_LABELS[job.nextStatus] + '로'
                : isExternalAttestation
                    ? STATUS_LABELS[job.nextStatus] + ' 확인 기록'
                : STATUS_LABELS[job.nextStatus] + '로 이동';
        }
    }

    async function createJob(event) {
        event.preventDefault();
        const form = event.currentTarget;
        if (!form.reportValidity()) return;
        const values = Object.fromEntries(new FormData(form).entries());
        const payload = {
            ...values,
            themeCode: emptyToNull(values.themeCode),
            sourceAssetKey: emptyToNull(values.sourceAssetKey),
            previewAssetKey: emptyToNull(values.previewAssetKey),
            targetItemId: values.targetItemId ? Number(values.targetItemId) : null,
            expectedOldAssetKey: emptyToNull(values.expectedOldAssetKey)
        };
        try {
            const created = await sendJson('/admin/asset-foundry/jobs', 'POST', payload);
            jobs.unshift(created);
            selectedJobId = created.id;
            form.reset();
            updateKindHint();
            newJobDialog.close();
            renderAll();
            showSuccess('새 manifest 작업을 등록했습니다.');
        } catch (error) {
            showError(error.message);
        }
    }

    function openQaDialog() {
        const job = selectedJob();
        if (!job) return;
        const example = {
            checks: [
                {code: 'CANVAS', label: '캔버스 규격', result: 'PASS', score: 100, required: true, message: '규격 일치'},
                {code: 'RN_86PX_MOTION', label: '86px 움직임 가시성', result: 'PASS', score: 92, required: true, message: 'visible delta 4.8%'}
            ]
        };
        document.getElementById('qa-report-json').value = JSON.stringify(example, null, 2);
        qaReportDialog.showModal();
    }

    async function submitQaReport(event) {
        event.preventDefault();
        const job = selectedJob();
        if (!job) return;
        try {
            const parsed = JSON.parse(document.getElementById('qa-report-json').value);
            const updated = await sendJson(`/admin/asset-foundry/jobs/${job.id}/qa`, 'PUT', {
                expectedRevision: job.revision,
                checks: parsed.checks
            });
            replaceJob(updated);
            qaReportDialog.close();
            renderAll();
            showSuccess('QA 리포트를 반영했습니다.');
        } catch (error) {
            showError(error instanceof SyntaxError ? 'QA report JSON 형식을 확인하세요.' : error.message);
        }
    }

    async function advanceSelected() {
        const job = selectedJob();
        if (!job?.nextStatus) return;
        await transition(job.nextStatus);
    }

    async function returnSelectedToDraft() {
        await transition('DRAFT');
    }

    async function transition(targetStatus) {
        const job = selectedJob();
        if (!job) return;
        const note = document.getElementById('transition-note').value.trim();
        if (!note) {
            showError('단계 변경 근거를 입력해주세요.');
            document.getElementById('transition-note').focus();
            return;
        }
        try {
            const updated = await sendJson(`/admin/asset-foundry/jobs/${job.id}/status`, 'PUT', {
                expectedRevision: job.revision,
                targetStatus,
                note
            });
            replaceJob(updated);
            document.getElementById('transition-note').value = '';
            renderAll();
            showSuccess(STATUS_LABELS[targetStatus] + ' 단계로 이동했습니다.');
        } catch (error) {
            showError(error.message);
        }
    }

    function replaceJob(updated) {
        jobs = jobs.map((job) => job.id === updated.id ? updated : job);
    }

    function selectedJob() {
        return jobs.find((job) => job.id === selectedJobId) || null;
    }

    function updateKindHint() {
        const kind = document.getElementById('new-job-kind').value;
        const hints = {
            ANIMATED_FURNITURE: '동적 가구는 -animated-vN.webp 버전 key를 사용합니다.',
            STATIC_FURNITURE: '정적 가구는 items/ 아래 PNG 또는 정적 WebP를 사용합니다.',
            CHARACTER_ANIMATION: '캐릭터 모션은 characters/{code}/animations/{idle|pose-cycle|wave}.webp 규칙입니다.',
            HOUSE_FRAME: '집 프레임은 house/ 아래 frame 이름을 포함한 투명 PNG를 사용합니다.'
        };
        document.getElementById('new-job-hint').textContent = hints[kind];
    }

    async function getJson(url) {
        return requestJson(url, {method: 'GET'});
    }

    async function sendJson(url, method, body) {
        const headers = {'Content-Type': 'application/json'};
        if (csrfToken && csrfHeader) headers[csrfHeader] = csrfToken;
        return requestJson(url, {method, headers, body: JSON.stringify(body)});
    }

    async function requestJson(url, options) {
        let response;
        try {
            response = await fetch(url, {...options, credentials: 'same-origin', redirect: 'follow'});
        } catch (error) {
            throw new Error('서버에 연결하지 못했습니다. 네트워크를 확인한 뒤 다시 시도해주세요.');
        }
        if (response.redirected && new URL(response.url).pathname === '/login') {
            throw new Error('관리자 세션이 만료되었습니다. 다시 로그인해주세요.');
        }
        if (response.status === 401 || response.status === 403) {
            throw new Error('관리자 세션이 만료되었거나 권한이 없습니다. 다시 로그인해주세요.');
        }
        if (!response.ok) {
            let message = `${url} HTTP ${response.status}`;
            try {
                const error = await response.json();
                message = error.message || message;
            } catch (ignored) {
                // JSON 에러가 아니면 상태 코드 메시지를 사용함.
            }
            throw new Error(message);
        }
        return response.status === 204 ? null : response.json();
    }

    function setRect(id, rect) {
        const element = document.getElementById(id);
        element.style.top = percent(rect.top);
        element.style.left = percent(rect.left);
        element.style.width = percent(rect.width);
        element.style.height = percent(rect.height);
    }

    function setSrc(id, key) {
        const image = document.getElementById(id);
        image.hidden = false;
        image.src = assetUrl(key);
    }

    function bindPreviewFallbacks() {
        const imageIds = [
            'foundry-room-background', 'foundry-room-wall', 'foundry-room-floor',
            'foundry-preview-asset', 'foundry-room-character', 'foundry-small-asset'
        ];
        imageIds.forEach((id) => {
            const image = document.getElementById(id);
            image.addEventListener('error', () => {
                image.hidden = true;
                if (id === 'foundry-preview-asset') {
                    document.getElementById('foundry-preview-unavailable').hidden = false;
                }
            });
            image.addEventListener('load', () => {
                image.hidden = false;
                if (id === 'foundry-preview-asset') {
                    document.getElementById('foundry-preview-unavailable').hidden = true;
                }
            });
        });
    }

    function assetUrl(key) {
        return s3BaseUrl + '/' + String(key).replace(/^\//, '');
    }

    function percent(value) {
        return (Number(value) * 100) + '%';
    }

    function setText(id, text) {
        document.getElementById(id).textContent = text;
    }

    function emptyToNull(value) {
        return value && value.trim() ? value.trim() : null;
    }

    function formatDate(value) {
        if (!value) return '방금 전';
        return new Intl.DateTimeFormat('ko-KR', {
            month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit'
        }).format(new Date(value));
    }

    function showError(message) {
        successBox.hidden = true;
        errorBox.textContent = message;
        errorBox.hidden = false;
    }

    function showSuccess(message) {
        errorBox.hidden = true;
        successBox.textContent = message;
        successBox.hidden = false;
        window.setTimeout(() => { successBox.hidden = true; }, 3500);
    }

    function escapeHtml(value) {
        const span = document.createElement('span');
        span.textContent = value ?? '';
        return span.innerHTML;
    }
})();
