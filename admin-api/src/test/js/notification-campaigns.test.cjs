const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const resources = resolve(__dirname, '../../main/resources');
const script = readFileSync(resolve(resources, 'static/js/notification-campaigns.js'), 'utf8');
const template = readFileSync(resolve(resources, 'templates/notification-campaigns.html'), 'utf8');
const API = '/admin/notification-campaigns';

// 네트워크 없이 실제 화면 스크립트의 입력/버튼 흐름을 실행함.
class Element {
    constructor(tagName = 'div') {
        this.tagName = tagName;
        this.value = '';
        this.checked = false;
        this.disabled = false;
        this.hidden = false;
        this.open = false;
        this.textContent = '';
        this.children = [];
        this.listeners = new Map();
    }
    set innerHTML(_) { throw new Error('Untrusted content must not be parsed as HTML'); }
    addEventListener(type, fn) {
        this.listeners.set(type, [...(this.listeners.get(type) || []), fn]);
    }
    append(...children) { this.children.push(...children); }
    replaceChildren(...children) { this.children = children; }
    focus() {}
    reportValidity() { return true; }
    showModal() { this.open = true; }
    close() { this.open = false; this.emit('close'); }
    async emit(type) {
        const event = { defaultPrevented: false, preventDefault() { this.defaultPrevented = true; } };
        for (const fn of this.listeners.get(type) || []) await fn(event);
        return event;
    }
}

function fixture(handler = () => []) {
    const nodes = new Map([...template.matchAll(/\bid="([^"]+)"/g)].map((match) => [match[1], new Element()]));
    const calls = [];
    let uuid = 0;
    nodes.get('campaignAudience').value = 'SELECTED';
    const document = {
        getElementById(id) {
            assert.ok(nodes.has(id), `Missing real template element: ${id}`);
            return nodes.get(id);
        },
        createElement: (tag) => new Element(tag),
        querySelector(selector) {
            if (selector === 'meta[name="_csrf"]') return { content: 'csrf-token' };
            if (selector === 'meta[name="_csrf_header"]') return { content: 'X-CSRF-TOKEN' };
            throw new Error(`Unexpected selector: ${selector}`);
        },
    };
    vm.runInNewContext(script, {
        document,
        crypto: { randomUUID: () => `00000000-0000-4000-8000-${String(++uuid).padStart(12, '0')}` },
        setTimeout: () => 1,
        clearTimeout: () => {},
        fetch: async (url, config) => {
            const call = { url, method: config.method || 'GET', headers: config.headers,
                body: config.body ? JSON.parse(config.body) : undefined };
            calls.push(call);
            const value = await handler(call);
            return { ok: true, status: 200, redirected: false, json: async () => value };
        },
    });
    const element = (id) => nodes.get(id);
    return {
        calls, element,
        flush: () => new Promise(setImmediate),
        click: (id) => element(id).emit('click'),
        change: async (id, value) => { element(id).value = value; await element(id).emit('input'); },
        fill: async function () {
            await this.change('campaignTitle', '운영 공지');
            await this.change('campaignBody', '공지 내용입니다.');
            await this.change('campaignUserIds', '12, 34, 12');
        },
    };
}

const counts = { recipientCount: 2, pushAllowedCount: 2, blockedCount: 0, tokenlessCount: 0 };
const draft = {
    id: 7, title: '운영 공지', body: '공지 내용입니다.', audience: 'SELECTED', pushEnabled: false,
    status: 'DRAFT', recipientCount: 2, createdCount: 0, createdAt: '2026-11-09T00:00:00Z', createdByUsername: 'admin',
};

test('처음 로드와 Enter 입력으로 발송하지 않고 푸시는 기본 미선택이다', async () => {
    const page = fixture();
    await page.flush();
    assert.equal(page.element('campaignPushEnabled').checked, false);
    assert.equal(page.element('campaignAudience').value, 'SELECTED');
    assert.equal(page.element('saveCampaign').disabled, true);
    assert.equal((await page.element('campaignForm').emit('submit')).defaultPrevented, true);
    assert.deepEqual(page.calls.map((call) => [call.method, call.url]), [['GET', API]]);
});

test('미리보기는 중복 ID를 제거하고 내용을 수정하면 저장 권한을 무효화한다', async () => {
    const page = fixture((call) => call.url.endsWith('/preview') ? counts : []);
    await page.flush();
    await page.fill();
    await page.click('previewCampaign');
    const preview = page.calls.find((call) => call.url.endsWith('/preview'));
    assert.deepEqual(preview.body.userIds, [12, 34]);
    assert.equal(preview.body.pushEnabled, false);
    assert.equal(preview.headers['X-CSRF-TOKEN'], 'csrf-token');
    assert.equal(page.element('saveCampaign').disabled, false);
    await page.change('campaignTitle', '수정된 공지');
    assert.equal(page.element('saveCampaign').disabled, true);
    await page.click('saveCampaign');
    assert.equal(page.calls.some((call) => call.method === 'POST' && call.url === API), false);
    assert.equal(page.calls.some((call) => call.url.endsWith('/send')), false);
});

test('전체 대상으로 바꾸면 숨겨진 사용자 ID가 요청에 남지 않는다', async () => {
    const page = fixture((call) => call.url.endsWith('/preview') ? counts : []);
    await page.flush();
    await page.fill();
    await page.change('campaignAudience', 'ALL');
    await page.click('previewCampaign');
    const request = page.calls.find((call) => call.url.endsWith('/preview'));
    assert.deepEqual(request.body.userIds, []);
    assert.equal(request.body.audience, 'ALL');
});

test('초안 저장 응답이 유실되면 같은 요청 ID로만 재시도하고 자동 발송하지 않는다', async () => {
    let saves = 0;
    const page = fixture((call) => {
        if (call.url.endsWith('/preview')) return counts;
        if (call.method === 'POST' && call.url === API) {
            if (++saves === 1) throw new Error('연결 끊김');
            return draft;
        }
        return [];
    });
    await page.flush();
    await page.fill();
    await page.click('previewCampaign');
    await page.click('saveCampaign');
    await page.click('saveCampaign');
    const creates = page.calls.filter((call) => call.method === 'POST' && call.url === API);
    assert.equal(creates.length, 2);
    assert.equal(creates[0].body.requestId, creates[1].body.requestId);
    assert.equal(page.calls.some((call) => call.url.endsWith('/send')), false);
});

test('별도 확인 전에는 발송하지 않고 더블클릭에도 발송 POST는 한 번뿐이다', async () => {
    let resolveSend;
    const page = fixture((call) => {
        if (call.url === API) return [draft];
        if (call.url === `${API}/7`) return draft;
        if (call.url.endsWith('/send')) return new Promise((resolve) => { resolveSend = resolve; });
        return counts;
    });
    await page.flush();
    const reviewButton = page.element('campaignRows').children[0].children[5].children[0];
    await reviewButton.emit('click');
    assert.equal(page.element('campaignSendDialog').open, true);
    await page.click('confirmCampaignSendButton');
    assert.equal(page.calls.some((call) => call.url.endsWith('/send')), false);
    page.element('confirmCampaignSend').checked = true;
    await page.element('confirmCampaignSend').emit('change');
    const firstClick = page.click('confirmCampaignSendButton');
    const secondClick = page.click('confirmCampaignSendButton');
    assert.equal(page.calls.filter((call) => call.url.endsWith('/send')).length, 1);
    const send = page.calls.find((call) => call.url.endsWith('/send'));
    assert.deepEqual(send.body, { confirmed: true, expectedRecipientCount: 2 });
    resolveSend({ ...draft, status: 'SENDING' });
    await Promise.all([firstClick, secondClick]);
});

test('발송 응답 유실 시 자동 재전송하지 않고 조회된 처리 상태로 수렴한다', async () => {
    let sendAttempted = false;
    const page = fixture((call) => {
        if (call.url === API) return [draft];
        if (call.url === `${API}/7`) return { ...draft, status: sendAttempted ? 'SENDING' : 'DRAFT' };
        if (call.url.endsWith('/send')) { sendAttempted = true; throw new Error('연결 끊김'); }
        return [];
    });
    await page.flush();
    await page.element('campaignRows').children[0].children[5].children[0].emit('click');
    page.element('confirmCampaignSend').checked = true;
    await page.click('confirmCampaignSendButton');
    assert.equal(page.calls.filter((call) => call.url.endsWith('/send')).length, 1);
    assert.equal(page.element('campaignSendDialog').open, false);
    assert.match(page.element('campaignMessage').textContent, /이미 발송 처리/);
});

test('사용자 입력을 미리보기와 이력에 HTML로 해석하지 않고 표시한다', async () => {
    const payload = '<img src=x onerror=alert(1)>';
    const page = fixture(() => [{ ...draft, title: payload, body: payload }]);
    await page.flush();
    await page.change('campaignTitle', payload);
    await page.change('campaignBody', payload);
    assert.equal(page.element('previewTitle').textContent, payload);
    assert.equal(page.element('previewBody').textContent, payload);
    const cell = page.element('campaignRows').children[0].children[0];
    assert.equal(cell.children[0].textContent, `#7 ${payload}`);
    assert.equal(cell.children[1].textContent, payload);
});

test('작성자와 다른 발송 승인자를 표시하고 이전 초안의 null 감사 필드도 처리한다', async () => {
    const page = fixture(() => [
        { ...draft, status: 'COMPLETED', approvedByUsername: 'second-admin', approvedAt: '2026-11-09T01:00:00Z' },
        { ...draft, id: 8, approvedByUsername: null, approvedAt: null },
    ]);
    await page.flush();
    const rows = page.element('campaignRows').children;
    assert.equal(rows[0].children[4].children[0].textContent, '작성: admin');
    assert.equal(rows[0].children[4].children[2].textContent, '발송 승인: second-admin');
    assert.equal(rows[1].children[4].children.length, 2);
    assert.equal(rows[1].children[4].children.some((node) => /null|undefined/.test(node.textContent)), false);
});
