(() => {
    'use strict';

    const API = '/admin/notification-campaigns';
    const byId = (id) => document.getElementById(id);
    const form = byId('campaignForm');
    const fields = byId('campaignFields');
    const title = byId('campaignTitle');
    const body = byId('campaignBody');
    const audience = byId('campaignAudience');
    const userIds = byId('campaignUserIds');
    const pushEnabled = byId('campaignPushEnabled');
    const previewButton = byId('previewCampaign');
    const saveButton = byId('saveCampaign');
    const dialog = byId('campaignSendDialog');
    const confirmCheckbox = byId('confirmCampaignSend');
    const confirmButton = byId('confirmCampaignSendButton');
    const cancelButton = byId('cancelCampaignSend');
    const csrfToken = document.querySelector('meta[name="_csrf"]').content;
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]').content;
    let revision = 0;
    let previewedPayload = null;
    let requestId = null;
    let savedDraft = null;
    let dialogCampaign = null;
    let busy = false;
    let sending = false;
    let listLoading = false;
    let pollTimer = null;

    // 폼의 Enter 키는 발송이나 초안 저장을 실행하지 않음.
    form.addEventListener('submit', (event) => event.preventDefault());
    for (const control of [title, body, audience, userIds, pushEnabled]) {
        control.addEventListener('input', invalidatePreview);
        control.addEventListener('change', invalidatePreview);
    }
    previewButton.addEventListener('click', preview);
    saveButton.addEventListener('click', saveDraft);
    byId('refreshCampaigns').addEventListener('click', loadCampaigns);
    confirmCheckbox.addEventListener('change', updateButtons);
    confirmButton.addEventListener('click', sendCampaign);
    cancelButton.addEventListener('click', () => { if (!sending) dialog.close(); });
    dialog.addEventListener('cancel', (event) => { if (sending) event.preventDefault(); });
    dialog.addEventListener('close', () => {
        dialogCampaign = null;
        confirmCheckbox.checked = false;
        updateButtons();
    });

    updateFormPreview();
    updateButtons();
    loadCampaigns();

    function invalidatePreview() {
        revision += 1;
        previewedPayload = null;
        requestId = null;
        savedDraft = null;
        byId('previewCounts').hidden = true;
        byId('previewStatus').textContent = '내용이 변경되었습니다. 대상과 내용을 다시 확인해 주세요.';
        byId('previewRequirement').textContent = '미리보기를 확인한 뒤 초안을 저장할 수 있습니다.';
        clearMessage();
        updateFormPreview();
        updateButtons();
    }

    function updateFormPreview() {
        byId('titleCount').textContent = `${title.value.length} / 255`;
        byId('bodyCount').textContent = `${body.value.length} / 1000`;
        byId('previewTitle').textContent = title.value.trim() || '제목을 입력해 주세요';
        byId('previewBody').textContent = body.value.trim() || '입력한 내용이 여기에 표시됩니다.';
        byId('selectedUsersField').hidden = audience.value === 'ALL';
    }

    function readPayload() {
        if (!form.reportValidity()) return null;
        const cleanTitle = title.value.trim();
        const cleanBody = body.value.trim();
        if (!cleanTitle || !cleanBody || cleanTitle.length > 255 || cleanBody.length > 1000) {
            throw new Error('제목은 1~255자, 내용은 1~1000자로 입력해 주세요.');
        }
        const ids = [];
        if (audience.value === 'SELECTED') {
            const values = userIds.value.trim().split(/[\s,]+/).filter(Boolean);
            if (values.length === 0) throw new Error('발송할 사용자 ID를 입력해 주세요.');
            for (const value of values) {
                if (!/^\d+$/.test(value) || !Number.isSafeInteger(Number(value)) || Number(value) < 1) {
                    throw new Error('사용자 ID는 양의 정수만 입력할 수 있습니다.');
                }
                ids.push(Number(value));
            }
            if (new Set(ids).size > 1000) throw new Error('사용자 ID는 한 번에 최대 1000개까지 선택할 수 있습니다.');
        }
        return {
            title: cleanTitle,
            body: cleanBody,
            audience: audience.value,
            userIds: [...new Set(ids)].sort((a, b) => a - b),
            pushEnabled: pushEnabled.checked,
        };
    }

    async function preview() {
        if (busy || sending) return;
        clearError();
        let payload;
        try { payload = readPayload(); } catch (error) { showError(error.message); return; }
        if (!payload) return;
        const requestedRevision = revision;
        setBusy(true);
        try {
            const result = await request(`${API}/preview`, { method: 'POST', body: payload });
            if (requestedRevision !== revision) return;
            previewedPayload = JSON.stringify(payload);
            byId('previewRecipientCount').textContent = `${result.recipientCount}명`;
            byId('previewPushAllowedCount').textContent = `${result.pushAllowedCount}명`;
            byId('previewBlockedCount').textContent = `${result.blockedCount}명`;
            byId('previewTokenlessCount').textContent = `${result.tokenlessCount}명`;
            byId('previewCounts').hidden = false;
            byId('previewStatus').textContent = payload.pushEnabled
                ? '푸시를 포함한 대상 정보를 확인했습니다. 아직 발송하지 않았습니다.'
                : '앱 알림함 저장 대상만 확인했습니다. 푸시는 선택하지 않았습니다.';
            byId('previewRequirement').textContent = '초안 저장 시 현재 대상이 고정됩니다. 저장 후 다시 내용을 확인하고 발송해 주세요.';
            if (result.recipientCount < 1) {
                previewedPayload = null;
                byId('previewStatus').textContent = '발송 가능한 사용자가 없습니다.';
            }
        } catch (error) {
            previewedPayload = null;
            showError(error.message);
        } finally {
            setBusy(false);
        }
    }

    async function saveDraft() {
        if (busy || sending || !previewedPayload || savedDraft) return;
        clearError();
        let payload;
        try { payload = readPayload(); } catch (error) { showError(error.message); return; }
        if (!payload) return;
        if (JSON.stringify(payload) !== previewedPayload) {
            invalidatePreview();
            showError('변경된 대상과 내용을 다시 미리보기 해주세요.');
            return;
        }
        // 응답 유실 후 다시 저장하더라도 같은 요청 ID를 재사용함.
        requestId ??= crypto.randomUUID();
        setBusy(true);
        try {
            savedDraft = await request(API, { method: 'POST', body: { ...payload, requestId } });
            showMessage(`초안 #${savedDraft.id}을 저장했습니다. 대상 ${savedDraft.recipientCount}명에게 아직 발송하지 않았습니다.`);
            byId('previewRequirement').textContent = '저장한 초안은 아래 이력의 발송 검토에서 확인할 수 있습니다.';
            await loadCampaigns();
        } catch (error) {
            showError(error.message);
        } finally {
            setBusy(false);
        }
    }

    async function openSendDialog(id) {
        if (busy || sending || dialog.open) return;
        clearError();
        setBusy(true);
        try {
            const campaign = await request(`${API}/${id}`);
            if (campaign.status !== 'DRAFT') {
                await loadCampaigns();
                showMessage('이미 발송을 시작한 알림입니다. 이력에서 처리 상태를 확인해 주세요.');
                return;
            }
            dialogCampaign = campaign;
            byId('sendDialogAudience').textContent = `초안 #${campaign.id}, ${audienceLabel(campaign.audience)} ${campaign.recipientCount}명에게 발송합니다.`;
            byId('sendDialogNoticeTitle').textContent = campaign.title;
            byId('sendDialogNoticeBody').textContent = campaign.body;
            byId('sendDialogChannel').textContent = campaign.pushEnabled
                ? '앱 알림함에 저장하고 푸시도 보냅니다. 푸시는 사용자의 알림 설정을 따릅니다.'
                : '앱 알림함에만 저장합니다. 푸시는 보내지 않습니다.';
            confirmCheckbox.checked = false;
            dialog.showModal();
            cancelButton.focus();
        } catch (error) {
            showError(error.message);
        } finally {
            setBusy(false);
        }
    }

    async function sendCampaign() {
        if (sending || busy || !dialogCampaign || !confirmCheckbox.checked) return;
        const campaign = dialogCampaign;
        sending = true;
        clearError();
        updateButtons();
        try {
            await request(`${API}/${campaign.id}/send`, {
                method: 'POST', body: { confirmed: true, expectedRecipientCount: campaign.recipientCount },
            });
            dialog.close();
            showMessage(`알림 #${campaign.id}의 발송 처리를 시작했습니다. 아래 이력에서 결과를 확인해 주세요.`);
        } catch (error) {
            // 발송 요청은 자동 재시도하지 않음. 응답 유실 시 조회로 실제 상태를 먼저 확인함.
            try {
                const current = await request(`${API}/${campaign.id}`);
                if (current.status !== 'DRAFT') {
                    dialog.close();
                    showMessage(`알림 #${campaign.id}은 이미 발송 처리 중이거나 완료되었습니다. 이력에서 결과를 확인해 주세요.`);
                } else {
                    dialog.close();
                    showError(`${error.message} 발송을 시작하지 않은 초안입니다. 다시 검토해 주세요.`);
                }
            } catch {
                dialog.close();
                showError('발송 요청 결과를 확인하지 못했습니다. 자동 재전송하지 않았습니다. 새로고침 후 해당 초안의 상태를 확인해 주세요.');
            }
        } finally {
            sending = false;
            updateButtons();
            await loadCampaigns();
        }
    }

    async function loadCampaigns() {
        if (listLoading) return;
        listLoading = true;
        byId('refreshCampaigns').disabled = true;
        clearTimeout(pollTimer);
        try {
            const campaigns = await request(API);
            byId('campaignRows').replaceChildren(...campaigns.map(buildCampaignRow));
            byId('campaignListStatus').textContent = campaigns.length
                ? `${campaigns.length}개. 한국 시간 기준으로 표시합니다.` : '아직 저장한 초안이 없습니다.';
            if (campaigns.some((campaign) => campaign.status === 'SENDING')) {
                pollTimer = setTimeout(loadCampaigns, 3000);
            }
        } catch (error) {
            byId('campaignListStatus').textContent = `목록을 불러오지 못했습니다. ${error.message}`;
        } finally {
            listLoading = false;
            byId('refreshCampaigns').disabled = false;
        }
    }

    function buildCampaignRow(campaign) {
        const row = document.createElement('tr');
        const noticeCell = element('td');
        noticeCell.append(element('strong', `#${campaign.id} ${campaign.title}`), element('p', campaign.body, 'campaign-row-body'));
        const audienceCell = element('td');
        audienceCell.append(element('div', `${audienceLabel(campaign.audience)} ${campaign.recipientCount}명`),
            element('div', campaign.pushEnabled ? '알림함 + 푸시' : '알림함만', 'campaign-row-body'));
        const statusCell = element('td');
        const statuses = { DRAFT: '초안', SENDING: '처리 중', COMPLETED: '처리 완료' };
        statusCell.append(element('span', statuses[campaign.status] || campaign.status, 'campaign-status'));
        const resultCell = element('td');
        const lines = [
            ['알림함 저장', campaign.createdCount], ['푸시 접수', campaign.pushSentCount],
            ['푸시 실패', campaign.pushFailedCount], ['설정 차단', campaign.pushBlockedCount],
            ['푸시 대기', campaign.pushPendingCount], ['푸시 미선택', campaign.pushNotRequestedCount],
            ['대상 제외', campaign.skippedCount], ['처리 실패', campaign.processingFailedCount],
        ];
        for (const [label, count] of lines) resultCell.append(element('div', `${label} ${count ?? 0}명`, 'campaign-result-line'));
        const createdCell = element('td');
        createdCell.append(element('div', `작성: ${campaign.createdByUsername || '-'}`), element('div', formatDate(campaign.createdAt), 'campaign-row-body'));
        if (campaign.approvedByUsername || campaign.approvedAt) {
            createdCell.append(element('div', `발송 승인: ${campaign.approvedByUsername || '(기록 없음)'}`, 'campaign-row-body'),
                element('div', formatDate(campaign.approvedAt), 'campaign-row-body'));
        }
        const actionCell = element('td');
        if (campaign.status === 'DRAFT') {
            const button = element('button', '발송 검토', 'btn-ghost');
            button.type = 'button';
            button.addEventListener('click', () => openSendDialog(campaign.id));
            actionCell.append(button);
        } else {
            actionCell.textContent = '-';
        }
        row.append(noticeCell, audienceCell, statusCell, resultCell, createdCell, actionCell);
        return row;
    }

    function setBusy(value) { busy = value; updateButtons(); }

    function updateButtons() {
        fields.disabled = busy || sending;
        previewButton.disabled = busy || sending;
        saveButton.disabled = busy || sending || !previewedPayload || !!savedDraft;
        saveButton.textContent = savedDraft ? '초안 저장됨' : '초안 저장';
        confirmButton.disabled = busy || sending || !dialogCampaign || !confirmCheckbox.checked;
        confirmButton.textContent = sending ? '발송 요청 중…' : '발송하기';
        confirmCheckbox.disabled = sending;
        cancelButton.disabled = sending;
    }

    async function request(url, options = {}) {
        const config = { credentials: 'same-origin', ...options };
        if (options.body !== undefined) {
            config.headers = { 'Content-Type': 'application/json', [csrfHeader]: csrfToken };
            config.body = JSON.stringify(options.body);
        }
        const response = await fetch(url, config);
        if (response.redirected || response.status === 401) throw new Error('관리자 세션이 만료되었습니다. 다시 로그인해 주세요.');
        if (!response.ok) {
            let message = `요청 실패 (HTTP ${response.status})`;
            try { message = (await response.json()).message || message; } catch { /* JSON 외 오류는 상태 코드로 표시함. */ }
            throw new Error(message);
        }
        return response.json();
    }

    function element(tag, text = '', className = '') {
        const node = document.createElement(tag);
        node.textContent = text;
        if (className) node.className = className;
        return node;
    }

    function audienceLabel(value) { return value === 'ALL' ? '전체 사용자' : '선택한 사용자'; }
    function formatDate(value) {
        if (!value) return '-';
        return new Date(value).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' });
    }
    function showError(message) { byId('campaignError').textContent = message; byId('campaignError').hidden = false; }
    function clearError() { byId('campaignError').hidden = true; }
    function showMessage(message) { byId('campaignMessage').textContent = message; byId('campaignMessage').hidden = false; }
    function clearMessage() { byId('campaignMessage').hidden = true; }
})();
