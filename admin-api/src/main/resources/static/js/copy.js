// 클립보드 복사 — navigator.clipboard 는 secure context(HTTPS/localhost) 전용이라
// HTTP 로 접속하는 dev admin 에서는 없다. 그 경우 임시 textarea + execCommand 로 복사한다.
function copyText(text) {
    if (navigator.clipboard && window.isSecureContext) {
        return navigator.clipboard.writeText(text).then(() => true, () => false);
    }
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.setAttribute('readonly', '');
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    let ok = false;
    try {
        ok = document.execCommand('copy');
    } catch (e) {
        ok = false;
    }
    ta.remove();
    return Promise.resolve(ok);
}
