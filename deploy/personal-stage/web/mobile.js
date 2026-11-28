'use strict';
const code = location.hash.slice(1);
history.replaceState(null, '', '/');
const get = id => document.getElementById(id);
let token;
let nickname;
async function api(path, method = 'GET', body, headers = {}) {
  const options = {method, cache: 'no-store', credentials: 'omit', headers};
  if (token) options.headers.Authorization = 'Bearer ' + token;
  if (body !== undefined) {
    options.headers['Content-Type'] = 'application/json';
    options.body = JSON.stringify(body);
  }
  const response = await fetch(path, options);
  if (!response.ok) throw new Error('HTTP ' + response.status);
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}
get('login').onclick = async () => {
  if (!code) {
    get('status').textContent = 'Mac에서 발급한 테스트 링크를 다시 열어 주세요.';
    return;
  }
  get('login').disabled = true;
  try {
    const session = await api('/rehearsal/session', 'POST', {}, {Authorization: 'Basic ' + btoa('rehearsal:' + code)});
    token = session.accessToken;
    const member = await api('/api/v1/me');
    nickname = member.nickname || 'qa' + crypto.randomUUID().slice(0, 8);
    get('bio').disabled = get('save').disabled = false;
    get('status').textContent = '테스트 회원 로그인 및 내 정보 조회 성공';
  } catch (error) {
    token = undefined;
    get('login').disabled = false;
    get('status').textContent = '로그인 또는 조회 실패 · ' + error.message;
  }
};
get('save').onclick = async () => {
  const bio = get('bio').value.trim();
  if (!bio) return;
  get('save').disabled = true;
  try {
    await api('/api/v1/me', 'PUT', {nickname, bio});
    const member = await api('/api/v1/me');
    if (member.bio !== bio) throw new Error('저장한 값과 조회한 값이 다릅니다.');
    get('status').textContent = '저장 및 재조회 성공 · ' + member.bio;
  } catch (error) {
    get('status').textContent = '저장 또는 재조회 실패 · ' + error.message;
  } finally {
    get('save').disabled = false;
  }
};
