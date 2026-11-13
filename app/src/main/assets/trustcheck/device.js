export function identifyBrowser(ua, touchPoints = 0) {
  const text = String(ua || '');
  let os = 'Неизвестно';
  if (/Android/i.test(text)) os = 'Android';
  else if (/iPhone|iPod/i.test(text)) os = 'iOS';
  else if (/iPad/i.test(text) || (/Macintosh/i.test(text) && touchPoints > 1)) os = 'iPadOS';
  else if (/Windows/i.test(text)) os = 'Windows';
  else if (/Mac OS|Macintosh/i.test(text)) os = 'macOS';
  else if (/Linux/i.test(text)) os = 'Linux';
  let browser = 'Неизвестно';
  if (/Edg(?:e|A|iOS)?\//i.test(text)) browser = 'Microsoft Edge';
  else if (/OPR\//i.test(text)) browser = 'Opera';
  else if (/FxiOS\/|Firefox\//i.test(text)) browser = 'Firefox';
  else if (/CriOS\/|Chrome\/|Chromium\//i.test(text)) browser = 'Chrome / Chromium';
  else if (/Safari\//i.test(text)) browser = 'Safari';
  return { os, browser };
}
export async function collectBrowserInfo(nav = navigator, doc = document, cryptoApi = globalThis.crypto) {
  const touch = Number(nav.maxTouchPoints) || 0;
  const ua = identifyBrowser(nav.userAgent, touch);
  const connection = nav.connection || nav.mozConnection || nav.webkitConnection;
  const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone || null;
  const languages = Array.isArray(nav.languages) && nav.languages.length ? nav.languages.join(', ') : nav.language || null;
  const info = {
    os: ua.os, browser: ua.browser, timezone, languages,
    cookies: typeof nav.cookieEnabled === 'boolean' ? nav.cookieEnabled : null,
    touch: typeof nav.maxTouchPoints === 'number' ? touch > 0 : null,
    connection: connection?.effectiveType || connection?.type || null,
    dnt: nav.doNotTrack === '1' ? 'Включён' : nav.doNotTrack === '0' ? 'Выключен' : 'Не задан',
    webgl: null, canvas: null,
  };
  try {
    const gl = doc.createElement('canvas').getContext('webgl');
    const debug = gl?.getExtension('WEBGL_debug_renderer_info');
    if (debug) info.webgl = gl.getParameter(debug.UNMASKED_RENDERER_WEBGL) || null;
  } catch { /* browser may restrict WebGL */ }
  try {
    if (!cryptoApi?.subtle || typeof TextEncoder === 'undefined') return info;
    const canvas = doc.createElement('canvas'); canvas.width = 260; canvas.height = 48;
    const ctx = canvas.getContext('2d'); if (!ctx) return info;
    ctx.fillStyle = '#e5f0e5'; ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.fillStyle = '#1c6b52'; ctx.font = '16px Arial'; ctx.fillText('Agent Droid Check • 12345', 8, 25);
    ctx.globalCompositeOperation = 'multiply'; ctx.fillStyle = 'rgba(220,140,90,.7)'; ctx.beginPath(); ctx.arc(225, 22, 12, 0, Math.PI * 2); ctx.fill();
    const bytes = new TextEncoder().encode(canvas.toDataURL());
    const digest = new Uint8Array(await cryptoApi.subtle.digest('SHA-256', bytes));
    info.canvas = Array.from(digest.slice(0, 8), byte => byte.toString(16).padStart(2, '0')).join('');
  } catch { /* browser may restrict Canvas */ }
  return info;
}
