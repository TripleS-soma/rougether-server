(() => {
  const byId = id => document.getElementById(id);
  const csrfToken = document.querySelector('meta[name="_csrf"]').content;
  const csrfHeader = document.querySelector('meta[name="_csrf_header"]').content;
  let kind = 'suggestions';
  let cursor = null;
  let generation = 0;
  let loading = false;
  let publishing = false;
  function error(message) { byId('error').textContent = message; byId('error').hidden = !message; }
  async function request(path, options = {}) {
    const res = await fetch(path, { ...options, headers: {
      'Content-Type': 'application/json', [csrfHeader]: csrfToken, ...options.headers,
    }});
    if (res.redirected || (!res.headers.get('content-type')?.includes('application/json') && res.status !== 204)) {
      throw new Error('로그인을 다시 확인해 주세요.');
    }
    if (!res.ok) throw new Error('요청을 처리하지 못했습니다. 다시 시도해 주세요.');
    return res.status === 204 ? null : res.json();
  }
  function render(entry, entryKind) {
    const card = document.createElement('article');
    card.className = 'feedback-entry';
    const title = document.createElement('h4'); title.textContent = entry.title;
    const meta = document.createElement('p');
    const date = new Date(entry.createdAt).toLocaleString('ko-KR', {timeZone: 'Asia/Seoul'});
    meta.textContent = (entryKind === 'suggestions' ? `${entry.nickname || '회원'} (#${entry.userId}) · ` : '') + date;
    const body = document.createElement('p'); body.style.whiteSpace = 'pre-wrap'; body.textContent = entry.content;
    const remove = document.createElement('button'); remove.type = 'button'; remove.textContent = '삭제'; remove.className = 'btn-secondary';
    remove.addEventListener('click', async () => {
      if (!confirm(entryKind === 'updates' ? '이 업데이트를 삭제할까요? 앱에서도 더 이상 표시되지 않습니다.' : '이 건의를 삭제할까요? 삭제한 내용은 복구할 수 없습니다.')) return;
      remove.disabled = true;
      try { await request(`/admin/${entryKind}/${entry.id}`, {method: 'DELETE'}); if (kind === entryKind) await load(true); }
      catch (e) { error(e.message); remove.disabled = false; }
    });
    card.append(title, meta, body, remove); return card;
  }
  async function load(reset) {
    if (!reset && loading) return;
    const current = ++generation;
    const requestedKind = kind;
    const next = reset ? null : cursor;
    loading = true; error(''); byId('more').disabled = true;
    if (reset) { cursor = null; byId('entries').replaceChildren(); byId('empty').hidden = true; byId('more').hidden = true; }
    try {
      const page = await request(`/admin/${requestedKind}${next ? `?cursor=${next}` : ''}`);
      if (current !== generation) return;
      page.items.forEach(item => byId('entries').append(render(item, requestedKind)));
      cursor = page.nextCursor; byId('more').hidden = cursor === null;
      byId('empty').hidden = byId('entries').children.length > 0;
    } catch (e) { if (current === generation) error(e.message); }
    finally { if (current === generation) { loading = false; byId('more').disabled = false; } }
  }
  function select(value) {
    kind = value;
    byId('publishForm').hidden = kind !== 'updates';
    byId('listTitle').textContent = kind === 'updates' ? '앱에 게시된 업데이트' : '접수된 건의';
    byId('suggestionsTab').setAttribute('aria-pressed', String(kind === 'suggestions'));
    byId('updatesTab').setAttribute('aria-pressed', String(kind === 'updates'));
    byId('success').textContent = '';
    void load(true);
  }
  byId('suggestionsTab').onclick = () => select('suggestions');
  byId('updatesTab').onclick = () => select('updates');
  byId('reload').onclick = () => { void load(true); };
  byId('more').onclick = () => { void load(false); };
  byId('publishForm').onsubmit = async event => {
    event.preventDefault(); if (publishing) return;
    const title = byId('title').value.trim(); const content = byId('content').value.trim();
    if (!title || !content) { error('제목과 내용을 입력해 주세요.'); return; }
    publishing = true; byId('publish').disabled = true; byId('title').disabled = true; byId('content').disabled = true; error('');
    try {
      await request('/admin/updates', {method: 'POST', body: JSON.stringify({title, content})});
      byId('publishForm').reset(); byId('success').textContent = '업데이트를 게시했습니다.';
      if (kind === 'updates') await load(true);
    } catch (e) { error(e.message); }
    finally { publishing = false; byId('publish').disabled = false; byId('title').disabled = false; byId('content').disabled = false; }
  };
  select('suggestions');
})();
