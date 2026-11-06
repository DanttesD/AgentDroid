import { ALL_COUNTRIES, countryName } from './countries.js';
import { parseTrace, reputation, scoreFactors, cardFactors, totalReputation, serviceCountry, serviceAssessment, connectionFactors, scoreTier, describeCountry, maskIP, formatFlag } from './logic.js';
import { collectBrowserInfo } from './device.js';

const $ = (selector) => document.querySelector(selector);
const escapeHTML = value => String(value ?? '').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]);
const icon = name => `<svg aria-hidden="true"><use href="#i-${name}"></use></svg>`;
const providers = {
  chatgpt: { name: 'ChatGPT', owner: 'OpenAI', logo: '/assets/chatgpt-logo.svg', url: 'https://chatgpt.com/cdn-cgi/trace' },
  claude: { name: 'Claude', owner: 'Anthropic', logo: '/assets/claude-logo.svg', url: 'https://claude.ai/cdn-cgi/trace' },
};
const cardStorageKey = 'agent-droid-check-card';
function readCard() {
  try {
    const card = JSON.parse(sessionStorage.getItem(cardStorageKey));
    const country = String(card?.country || '').toUpperCase();
    return countryName(country) && ['physical', 'virtual'].includes(card?.type) ? { country, type: card.type } : null;
  } catch { return null; }
}
function saveCard(card) {
  try {
    if (card) sessionStorage.setItem(cardStorageKey, JSON.stringify(card));
    else sessionStorage.removeItem(cardStorageKey);
  } catch { /* The check still works in memory when storage is unavailable. */ }
}
const state = { selected: 'chatgpt', card: readCard(), hidden: false, refreshing: false, running: false, finished: false, cloudflare: null, device: null, serviceStatus: { chatgpt: null, claude: null }, webRTC: { state: 'idle', ips: [] }, dns: { state: 'idle', resolvers: [] }, services: { chatgpt: {}, claude: {} } };
const displayIP = ip => escapeHTML(state.hidden ? maskIP(ip) : (ip || '—'));
const safeString = value => typeof value === 'string' && value.trim() ? value.trim() : null;

async function request(url, type = 'json', timeout = 9000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeout);
  try {
    const response = await fetch(url, { cache: 'no-store', credentials: 'omit', referrerPolicy: 'no-referrer', signal: controller.signal });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return type === 'text' ? await response.text() : await response.json();
  } finally { clearTimeout(timer); }
}
async function getTrace(url) { const start = performance.now(); const trace = parseTrace(await request(url, 'text')); return { ...trace, latencyMs: Math.round(performance.now() - start) }; }
// Net.Coffee rejects uncached lookups from our server's IP, so the browser asks it directly
// and the server validates the answer; the server's own lookup is only a fallback.
async function getNetCoffee(ip) {
  try {
    const response = await fetch(`https://ip.net.coffee/api/iprisk/${encodeURIComponent(ip)}`, { credentials: 'omit', referrerPolicy: 'no-referrer', signal: AbortSignal.timeout(6000) });
    if (!response.ok) return null;
    const text = await response.text();
    return text.length <= 65536 ? JSON.parse(text) : null;
  } catch { return null; }
}
async function getRisk(ip) {
  const netcoffee = await getNetCoffee(ip);
  const response = await fetch('/api/reputation', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(netcoffee ? { ip, netcoffee } : { ip }), signal: AbortSignal.timeout(9000) });
  if (!response.ok) throw new Error('Локальная база недоступна');
  const result = await response.json();
  if (!result || result.ip !== ip) throw new Error('Нет данных о репутации');
  return result;
}
async function checkAll() {
  if (state.running) return;
  state.running = true; state.finished = false;
  state.cloudflare = null;
  state.services = { chatgpt: { attempted: true }, claude: { attempted: true } };
  $('#run-message').textContent = '';
  render();
  const [cf, gpt, claude] = await Promise.allSettled([
    getTrace('https://1.1.1.1/cdn-cgi/trace'),
    getTrace(providers.chatgpt.url), getTrace(providers.claude.url),
  ]);
  if (cf.status === 'fulfilled') state.cloudflare = cf.value;
  if (gpt.status === 'fulfilled') state.services.chatgpt.trace = gpt.value;
  if (claude.status === 'fulfilled') state.services.claude.trace = claude.value;
  render();
  const uniqueIPs = [...new Set(Object.values(state.services).map(item => item.trace?.ip).filter(Boolean))];
  const riskResults = await Promise.allSettled(uniqueIPs.map(getRisk));
  const riskByIP = new Map(uniqueIPs.map((ip, index) => [ip, riskResults[index].status === 'fulfilled' ? riskResults[index].value : null]));
  for (const result of Object.values(state.services)) if (result.trace) result.risk = riskByIP.get(result.trace.ip);
  state.running = false; state.finished = true;
  const failures = Object.entries(state.services).filter(([, item]) => !item.trace).map(([key]) => providers[key].name);
  const noRisk = Object.values(state.services).some(item => item.trace && !item.risk);
  const incompleteRisk = Object.values(state.services).some(item => item.trace && item.risk && reputation(item.risk) === null);
  const notices = [];
  if (failures.length === 2) notices.push('Проблемы с сетью: у вас нет доступа к ChatGPT и Claude. Проверьте интернет или VPN и повторите проверку.');
  else if (failures.length) notices.push(`Проблемы с сетью: у вас нет доступа к ${failures.join(', ')}. Проверьте интернет или VPN и повторите проверку.`);
  if (noRisk) notices.push('Не удалось получить оценку IP. Повторите проверку позже.');
  if (incompleteRisk) notices.push('Для оценки не хватает данных о типе сети или других основных признаках.');
  if (Object.values(state.services).some(item => item.risk?.dataStale)) notices.push('Локальные базы устарели, поэтому результаты могут быть неточными. Повторите проверку позже.');
  $('#run-message').textContent = notices.join(' ');
  render();
}
function renderServices() {
  $('#service-grid').innerHTML = Object.entries(providers).map(([key, provider]) => {
    const result = state.services[key], exitCountry = serviceCountry(result);
    const fromNetCoffee = result.risk?.scoreSource === 'netcoffee';
    const network = result.risk?.scoreSource === 'local' ? connectionFactors(key, result, state) : [];
    const score = totalReputation(result.risk);
    const paymentFactors = cardFactors(state.card, exitCountry);
    let status = serviceAssessment(key, result, network);
    if (status.tone === 'good' && paymentFactors.some(factor => factor.points > 0)) status = { tone: 'warn', label: 'Есть факторы' };
    else if (status.tone === 'good' && paymentFactors.some(factor => factor.points === null)) status = { tone: 'warn', label: 'Данные неполные' };
    if (state.running && !result.trace) status = { tone: '', label: 'Проверяем…' };
    const statusLabel = status.label;
    const noAccess = state.finished && result.attempted && !result.trace;
    const country = describeCountry(key, exitCountry);
    const location = result.trace ? (country.name === 'Не удалось определить' ? 'Регион неизвестен' : country.name) : (noAccess ? 'Сервис недоступен' : 'Ожидает запуска');
    const factors = scoreFactors(result.risk);
    const observations = fromNetCoffee ? [result.risk.is_vpn && 'VPN', result.risk.is_proxy && 'Прокси', result.risk.is_datacenter && 'Хостинг', result.risk.is_tor && 'Tor', result.risk.is_abuser && 'Злоупотребления'] : [...factors, ...network].map(factor => factor.points ? `${factor.label} −${factor.points}` : null);
    const reasons = observations.filter(Boolean).join(' · ');
    const tier = scoreTier(score);
    const scoreDescription = score === null ? (noAccess ? 'Проверьте интернет или VPN' : state.finished ? 'Не удалось получить оценку, повторите проверку' : 'Оценка появится после проверки') : reasons;
    const scoreNote = tier ? `<span class="score-risk tier-${tier.tone}">${escapeHTML(tier.label)}</span>` : noAccess ? `<span class="score-risk tier-bad">Проблемы с сетью: нет доступа к ${escapeHTML(provider.name)}</span>` : '';
    return `<button type="button" class="service-card ${state.selected === key ? 'selected' : ''}" data-service="${key}" aria-pressed="${state.selected === key}">
      <span class="service-top"><span class="service-logo ${key}"><img src="${provider.logo}" alt="" aria-hidden="true"></span><span class="service-name"><strong>${provider.name}</strong><small>${provider.owner} · данные подключения</small></span><span class="status-chip ${status.tone}">${status.tone === 'warn' ? icon('alert') : ''}${escapeHTML(statusLabel)}</span></span>
      <span class="service-separator" style="display:block"></span><span class="service-body"><span><span class="score-label">Общая оценка</span><span class="score-value ${tier ? `tier-${tier.tone}` : ''}" style="display:block">${score === null ? '—' : score} <span>/ 100</span></span>${scoreNote}</span><span class="service-description">${escapeHTML(scoreDescription)}</span></span>
      <span class="score-bar ${score === null ? 'empty' : ''}" style="display:block">${score === null ? '' : `<span class="score-marker" style="left:${score}%"></span>`}</span><span class="service-bottom"><span>${escapeHTML(location)}</span><span class="service-detail">Подробнее ${icon('arrow')}</span></span></button>`;
  }).join('');
}
function dataRow(label, value, cls = '') { return `<div class="data-row"><span>${escapeHTML(label)}</span><strong class="${cls}">${cls === 'warning' ? icon('alert') : ''}<span>${value}</span></strong></div>`; }
function signalRow(label, value) { const cls = value === true ? 'warning' : value === false ? 'positive' : 'unknown'; return `<div class="signal-row"><span>${escapeHTML(label)}</span><strong class="${cls}">${cls === 'warning' ? icon('alert') : ''}<span>${formatFlag(value)}</span></strong></div>`; }
function renderNetwork() {
  const service = state.selected, item = state.services[service], provider = providers[service];
  const trace = item.trace, risk = item.risk;
  const source = (label, field, fallback) => risk?.fieldSources?.[field] === 'netcoffee' ? label : `${label} · ${fallback}`;
  const geo = describeCountry(service, serviceCountry(item));
  let country = geo.name;
  if (geo.status === 'supported') country += geo.name === 'Украина' ? ' · есть исключения' : ' · в списке регионов';
  if (geo.status === 'unlisted') country += ' · нет в списке регионов';
  const isp = safeString(risk?.asOrganization) || safeString(risk?.company_name);
  const asn = risk?.asn ? `AS${String(risk.asn).replace(/^AS/i, '')}` : null;
  const cfText = state.cloudflare?.ip ? displayIP(state.cloudflare.ip) : '—';
  const serviceText = trace?.ip ? displayIP(trace.ip) : '—';
  const type = risk?.is_vpn === true ? 'Совпадение с VPN' : risk?.is_datacenter === true ? 'Хостинг / дата-центр' : 'Не определён';
  const categories = { hosting: 'Хостинг / облако', isp: 'Интернет-провайдер', business: 'Организация', education_research: 'Образование / наука', government_admin: 'Госорганизация' };
  const asnType = categories[risk?.asnCategory] ? `${categories[risk.asnCategory]} · ipverse` : null;
  $('#network-content').innerHTML = [
    dataRow(`IP для ${provider.name}`, serviceText, trace ? '' : 'unknown'),
    dataRow('Версия IP', risk?.ipVersion ? `IPv${escapeHTML(risk.ipVersion)}` : '—', risk?.ipVersion ? '' : 'unknown'),
    dataRow('Страна и поддержка', escapeHTML(country), geo.status === 'unlisted' ? 'warning' : geo.status === 'unknown' ? 'unknown' : ''),
    dataRow(source('Регион', 'region', 'DB-IP'), escapeHTML(risk?.region || 'Нет данных'), risk?.region ? '' : 'unknown'),
    dataRow(source('Город', 'city', 'DB-IP'), escapeHTML(risk?.city || 'Нет данных'), risk?.city ? '' : 'unknown'),
    dataRow('Признак IP', escapeHTML(type), type === 'Не определён' ? 'unknown' : 'warning'),
    dataRow('Тип сети по ASN', escapeHTML(asnType || 'Нет данных'), asnType ? '' : 'unknown'),
    dataRow('ASN', escapeHTML(asn || 'Нет данных'), asn ? '' : 'unknown'),
    dataRow('Организация сети', escapeHTML(isp || 'Нет данных'), isp ? '' : 'unknown'),
    dataRow('IP Cloudflare', cfText, state.cloudflare ? '' : 'unknown'),
  ].join('');
  $('#signals-content').innerHTML = [signalRow(source('VPN', 'is_vpn', 'X4B / FFraud'), risk?.is_vpn), signalRow(source('Прокси', 'is_proxy', 'FFraud'), risk?.is_proxy), signalRow(source('Tor', 'is_tor', 'Tor Project / FFraud'), risk?.is_tor), signalRow('Хостинг / дата-центр', risk?.is_datacenter), signalRow('Краулер / бот', risk?.is_crawler), signalRow(risk?.fieldSources?.is_abuser === 'netcoffee' ? 'Злоупотребления' : 'Адрес в базе FFraud', risk?.is_abuser), signalRow('Сеть в списке FFraud', risk?.is_abuse_asn)].join('');
  const date = risk?.sourceUpdatedAt ? new Date(risk.sourceUpdatedAt).toLocaleDateString('ru-RU') : null;
  $('.signal-panel .panel-header p').textContent = risk?.providerAvailable ? 'Базы репутации IP' : date ? `Локальные базы · получены ${date}` : 'По локальным открытым базам';
  $('#toggle-ip').innerHTML = `${icon('eye')} ${state.hidden ? 'Показать IP' : 'Скрыть IP'}`;
  $('#toggle-ip').setAttribute('aria-pressed', String(state.hidden));
  renderExtra();
}
function renderExtra() {
  const rtc = state.webRTC, targetIP = state.services[state.selected].trace?.ip;
  let content = 'Проверка ещё не запускалась.';
  if (rtc.state === 'running') content = 'Определяем адрес WebRTC…';
  if (rtc.state === 'failed') content = 'Не удалось определить публичный адрес. Это не подтверждает отсутствие утечки.';
  if (rtc.state === 'done') {
    const match = !targetIP ? 'Сначала проверьте подключение, чтобы сравнить адреса.' : rtc.ips.every(ip => ip === targetIP) ? `Совпадает с маршрутом ${providers[state.selected].name}.` : `Отличается от маршрута ${providers[state.selected].name}. Проверьте настройки сети.`;
    content = `<strong>Адрес WebRTC:</strong> ${rtc.ips.map(displayIP).join(', ')}<br>${escapeHTML(match)}`;
  }
  $('#webrtc-result').innerHTML = content;
  const dns = state.dns;
  let dnsContent = 'Проверка ещё не запускалась.';
  if (dns.state === 'running') dnsContent = 'Ищем DNS-резолверы…';
  if (dns.state === 'failed') dnsContent = 'Не удалось получить ответ DNS-проверки. Это не означает, что утечек нет.';
  if (dns.state === 'done') dnsContent = dns.resolvers.map(item => `<div><strong>${displayIP(item.ip)}</strong>${item.organization ? ` · ${escapeHTML(item.organization)}` : ''}${item.country ? ` · ${escapeHTML(item.country)}` : ''}</div>`).join('');
  $('#dns-result').innerHTML = dnsContent;

}
async function checkWebRTC() {
  if (state.webRTC.state === 'running') return;
  state.webRTC = { state: 'running', ips: [] }; renderExtra(); renderServices();
  let peer; let timer;
  try {
    if (typeof RTCPeerConnection === 'undefined') throw new Error('WebRTC is unavailable');
    peer = new RTCPeerConnection({ iceServers: [{ urls: 'stun:stun.cloudflare.com:3478' }] });
    const ips = new Set();
    const gathering = new Promise(resolve => {
      timer = setTimeout(resolve, 6000);
      peer.onicecandidate = event => {
        if (!event.candidate) { resolve(); return; }
        if (event.candidate.type !== 'srflx' && !/\btyp srflx\b/.test(event.candidate.candidate)) return;
        const ip = event.candidate.address || event.candidate.candidate.split(' ')[4];
        if (ip && /^[a-f\d:.]+$/i.test(ip) && (ip.includes('.') || ip.includes(':'))) ips.add(ip);
      };
    });
    peer.createDataChannel('check');
    await peer.setLocalDescription(await peer.createOffer());
    await gathering;
    state.webRTC = { state: ips.size ? 'done' : 'failed', ips: [...ips] };
  } catch { state.webRTC = { state: 'failed', ips: [] }; }
  finally { clearTimeout(timer); peer?.close(); renderExtra(); renderServices(); }
}
async function checkDNS() {
  if (state.dns.state === 'running') return;
  state.dns = { state: 'running', resolvers: [] }; renderExtra(); renderServices();
  try {
    const started = await fetch('/api/dns/start', { method: 'POST', signal: AbortSignal.timeout(12000) });
    if (!started.ok) throw new Error('DNS service unavailable');
    const session = await started.json();
    if (!/^[a-z0-9]{10}$/.test(session.testId) || !Array.isArray(session.hostnames) || session.hostnames.length === 0 || session.hostnames.length > 16 || !session.hostnames.every(host => new RegExp(`^${session.testId}-[aq][0-9]{2}\\.t\\.dnsleak\\.dev$`).test(host))) throw new Error('Invalid session');
    // Resolving these hostnames is the test; the HTTPS connection can fail by design.
    await Promise.allSettled(session.hostnames.map(host => fetch(`https://${host}/`, { mode: 'no-cors', credentials: 'omit', cache: 'no-store', signal: AbortSignal.timeout(4000) })));
    let report;
    for (let i = 0; i < 3; i++) {
      if (i) await new Promise(resolve => setTimeout(resolve, 1000));
      const response = await fetch(`/api/dns/result/${session.testId}`, { signal: AbortSignal.timeout(12000) });
      if (!response.ok) throw new Error('DNS report unavailable');
      report = await response.json();
      if (Array.isArray(report.resolvers) && report.resolvers.length && report.lookups >= session.hostnames.length) break;
    }
    state.dns = Array.isArray(report?.resolvers) && report.resolvers.length ? { state: 'done', resolvers: report.resolvers, verdict: report.verdict, complete: report.lookups >= session.hostnames.length } : { state: 'failed', resolvers: [] };
  } catch { state.dns = { state: 'failed', resolvers: [] }; }
  renderExtra(); renderServices();
}
function renderServiceStatus() {
  const indicators = { none: 'Работает', minor: 'Частичные неполадки', major: 'Сбой', critical: 'Серьёзный сбой', maintenance: 'Технические работы' };
  $('#status-content').innerHTML = Object.entries(providers).map(([key, provider]) => {
    const trace = state.services[key].trace, status = state.serviceStatus[key];
    const network = trace ? `Ответ · ${trace.latencyMs} мс` : state.running ? 'Проверяем…' : 'Не удалось проверить';
    const global = status?.indicator in indicators ? indicators[status.indicator] : status?.state === 'loading' ? 'Проверяем…' : 'Нет данных';
    const globalClass = status?.indicator === 'none' ? 'positive' : status?.indicator ? 'warning' : 'unknown';
    return `<div class="status-card"><h3>${provider.name}</h3>${dataRow('Сетевой запрос', escapeHTML(network), trace ? 'positive' : 'unknown')}${dataRow('Общий статус сервиса', escapeHTML(global), globalClass)}</div>`;
  }).join('');
}
function renderDevice() {
  const d = state.device;
  const flag = value => value === true ? 'Да' : value === false ? 'Нет' : 'Нет данных';
  const rows = [
    ['Операционная система', d?.os], ['Браузер', d?.browser], ['Языки', d?.languages],
    ['Часовой пояс', d?.timezone], ['Cookies включены', d ? flag(d.cookies) : null],
    ['Сенсорный ввод', d ? flag(d.touch) : null], ['Тип сети', d?.connection],
    ['Do Not Track', d?.dnt], ['WebGL renderer', d?.webgl], ['Canvas ID', d?.canvas],
  ];
  $('#device-content').innerHTML = rows.map(([label, value]) => dataRow(label, escapeHTML(value || 'Нет данных'), value ? '' : 'unknown')).join('');
}
async function checkStatuses() {
  const direct = { chatgpt: 'https://status.openai.com/api/v2/status.json', claude: 'https://status.claude.com/api/v2/status.json' };
  const valid = new Set(['none', 'minor', 'major', 'critical', 'maintenance']);
  const setStatus = (key, indicator) => {
    state.serviceStatus[key] = valid.has(indicator) ? { indicator } : { state: 'failed' };
  };
  state.serviceStatus = { chatgpt: { state: 'loading' }, claude: { state: 'loading' } }; renderServiceStatus();
  let proxied = null;
  try { proxied = await request('/api/status'); } catch { /* the public APIs remain as a fallback */ }
  await Promise.allSettled(Object.entries(direct).map(async ([key, url]) => {
    if (proxied && typeof proxied[key]?.indicator === 'string') {
      setStatus(key, proxied[key].indicator);
    } else {
      try { const body = await request(url); setStatus(key, body?.status?.indicator); }
      catch { setStatus(key, null); }
    }
    renderServiceStatus();
  }));
}
async function updateDevice() {
  try { state.device = await collectBrowserInfo(); } catch { state.device = null; }
  renderDevice();
}
async function runChecks() {
  if (state.refreshing) return;
  state.refreshing = true; render();
  try {
    const results = await Promise.allSettled([checkAll(), checkDNS(), checkWebRTC(), checkStatuses(), updateDevice()]);
    if (results[0].status === 'rejected') { state.running = false; $('#run-message').textContent = 'Не удалось завершить сетевую проверку. Попробуйте ещё раз.'; }
  } finally { state.refreshing = false; render(); }
}
function render() {
  const button = $('#run-check');
  button.disabled = state.refreshing;
  button.innerHTML = state.refreshing ? `<span class="run-label">Проверяем подключение…</span><svg class="svg-spin"><use href="#i-refresh"/></svg>` : `<span class="run-label">Обновить проверку</span>${icon('refresh')}`;
  renderServices(); renderNetwork(); renderServiceStatus(); renderDevice(); renderCardState();
}
function initCountries() {
  const select = $('#card-country');
  const countries = ALL_COUNTRIES.map(code => ({ code, name: countryName(code) })).filter(item => item.name).sort((a, b) => a.name.localeCompare(b.name, 'ru'));
  for (const { code, name } of countries) select.add(new Option(name, code));
}
function renderCardState() {
  $('#card-state').textContent = state.card ? `${countryName(state.card.country)} · ${state.card.type === 'virtual' ? 'виртуальная' : 'пластиковая'}` : 'Карта не учитывается';
  $('#clear-card').hidden = !state.card;
}
function openCardDialog() {
  const form = $('#card-form');
  form.reset();
  if (state.card) {
    $('#card-country').value = state.card.country;
    form.querySelector(`input[name="card-type"][value="${state.card.type}"]`).checked = true;
  }
  $('#card-dialog').showModal();
}
function applyCard(event) {
  event.preventDefault();
  const country = $('#card-country').value, type = new FormData(event.currentTarget).get('card-type');
  if (!countryName(country) || !['physical', 'virtual'].includes(type)) return;
  state.card = { country, type };
  saveCard(state.card);
  $('#card-dialog').close();
  render();
}
$('#run-check').addEventListener('click', runChecks);
$('#service-grid').addEventListener('click', event => {
  const button = event.target.closest('[data-service]'); if (!button) return;
  state.selected = button.dataset.service; renderServices(); renderNetwork();
  $('#network').scrollIntoView({ behavior: 'smooth', block: 'start' });
});
$('#toggle-ip').addEventListener('click', () => { state.hidden = !state.hidden; renderNetwork(); });
$('#open-card').addEventListener('click', openCardDialog);
$('#close-card').addEventListener('click', () => $('#card-dialog').close());
$('#card-form').addEventListener('submit', applyCard);
$('#clear-card').addEventListener('click', () => { state.card = null; saveCard(null); $('#card-form').reset(); $('#card-dialog').close(); render(); });
$('#card-dialog').addEventListener('click', event => { if (event.target === $('#card-dialog')) $('#card-dialog').close(); });
initCountries(); render(); runChecks();
