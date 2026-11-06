import { countryName, regionStatus } from './countries.js';

export function parseTrace(text) {
  const pairs = Object.fromEntries(String(text).split(/\r?\n/).filter(line => line.includes('=')).map(line => { const i = line.indexOf('='); return [line.slice(0, i), line.slice(i + 1)]; }));
  const ip = pairs.ip?.trim();
  if (!ip || !/^[a-f\d:.]+$/i.test(ip) || (!ip.includes('.') && !ip.includes(':'))) throw new Error('Не удалось прочитать IP');
  return { ip, countryCode: /^[A-Z]{2}$/.test(pairs.loc || '') ? pairs.loc : null };
}
// These weights describe our local heuristic, not a probability or a provider account score.
export function scoreFactors(value) {
  const v = value || {};
  const amount = (flag, penalty) => flag === true ? penalty : flag === false ? 0 : null;
  return [
    { label: 'VPN', points: v.is_vpn === true ? (v.is_proxy === true ? 0 : v.is_datacenter === true ? 10 : 20) : amount(v.is_vpn, 20) },
    { label: 'Хостинг', points: amount(v.is_datacenter, 15) },
    { label: 'Прокси', points: amount(v.is_proxy, 30) },
    { label: 'Сеть в OpenProxyDB', points: v.is_proxy === true ? 0 : amount(v.is_proxy_block, 10) },
    { label: 'Tor', points: amount(v.is_tor, 35) },
    { label: 'IP в FFraud', points: amount(v.is_abuser, 35) },
    { label: 'Сеть в FFraud', points: v.is_abuser === true && v.is_abuse_asn === true ? 0 : amount(v.is_abuse_asn, 10), note: v.is_abuser === true && v.is_abuse_asn === true ? 'учтено в адресе' : null },
  ];
}
export function reputation(value) {
  if (Number.isInteger(value?.netcoffeeScore) && value.netcoffeeScore >= 0 && value.netcoffeeScore <= 100) return value.netcoffeeScore;
  if (!value || !['is_vpn', 'is_datacenter', 'is_abuser'].every(key => typeof value[key] === 'boolean')) return null;
  const deductions = scoreFactors(value).reduce((sum, factor) => sum + (factor.points || 0), 0);
  return Math.max(0, 100 - deductions);
}
export function serviceCountry(result) {
  for (const value of [result?.trace?.countryCode, result?.risk?.geoCountryCode]) {
    const code = String(value || '').toUpperCase();
    if (countryName(code)) return code;
  }
  return null;
}
export function cardFactors(card, countryCode) {
  const country = String(card?.country || '').toUpperCase();
  if (!countryName(country) || !['physical', 'virtual'].includes(card?.type)) return [];
  const location = String(countryCode || '').toUpperCase();
  return [
    { label: 'Виртуальная карта', points: card.type === 'virtual' ? 5 : 0 },
    { label: 'Страны карты и IP различаются', points: countryName(location) ? (country === location ? 0 : 5) : null },
  ];
}
// The displayed score is the provider trust_score, untouched: no card, network or local adjustments.
export function totalReputation(value) {
  const score = value?.netcoffeeScore;
  if (!Number.isInteger(score) || score < 0 || score > 100) return null;
  // Restricted regions always score zero.
  if (['CN', 'HK', 'MO', 'RU', 'KP', 'IR', 'SY', 'CU', 'BY', 'VE'].includes(value.netcoffeeCountryCode)) return 0;
  return score;
}
export function scoreTier(score) {
  if (!Number.isInteger(score)) return null;
  if (score >= 75) return { tone: 'good', label: 'Низкая вероятность бана' };
  if (score >= 60) return { tone: 'mid', label: 'Средняя вероятность бана' };
  return { tone: 'bad', label: 'Высокая вероятность бана' };
}
// URL normalises IPv6 spellings; comparisons only make sense within the same address family.
function canonicalAddress(address) {
  if (typeof address !== 'string') return null;
  try {
    if (/^\d{1,3}(?:\.\d{1,3}){3}$/.test(address)) return { family: 4, value: new URL(`http://${address}/`).hostname };
    if (address.includes(':') && /^[a-f\d:.]+$/i.test(address)) return { family: 6, value: new URL(`http://[${address}]/`).hostname };
  } catch { /* Invalid input remains unknown. */ }
  return null;
}
export function connectionFactors(service, result, connection) {
  const exit = canonicalAddress(result?.trace?.ip);
  const exitCountry = serviceCountry(result);
  const cf = canonicalAddress(connection?.cloudflare?.ip);
  const cfCountry = String(connection?.cloudflare?.countryCode || '').toUpperCase();
  const region = regionStatus(service, exitCountry);
  const regionPoints = region === 'unknown' ? null : region === 'unlisted' ? 100 : 0;

  let routePoints = null;
  if (exit && cf && exit.family === cf.family) {
    if (exit.value === cf.value) routePoints = 0;
    else if (exitCountry && countryName(cfCountry)) routePoints = exitCountry === cfCountry ? 0 : 15;
  }

  let rtcPoints = null;
  const rtc = connection?.webRTC;
  if (exit && rtc?.state === 'done' && Array.isArray(rtc.ips)) {
    const comparable = rtc.ips.map(canonicalAddress).filter(ip => ip && ip.family === exit.family);
    if (comparable.length) rtcPoints = comparable.some(ip => ip.value !== exit.value) ? 25 : 0;
  }

  let dnsPoints = null;
  let dnsLabel = 'DNS не удалось сравнить';
  const dns = connection?.dns;
  if (dns?.state === 'done' && Array.isArray(dns.resolvers)) {
    const resolvers = dns.resolvers.filter(item => item.role !== 'observer');
    const countries = resolvers.filter(item => item.anycast === false && countryName(item.countryCode)).map(item => String(item.countryCode).toUpperCase());
    if (dns.complete && dns.verdict === 'split') { dnsPoints = 15; dnsLabel = 'DNS: разные провайдеры'; }
    else if (exitCountry && countries.some(country => country !== exitCountry)) { dnsPoints = 10; dnsLabel = 'DNS в другой стране'; }
    else if (exitCountry && countries.length && dns.complete) { dnsPoints = 0; dnsLabel = 'Страна DNS совпадает'; }
  }
  return [
    { label: 'Регион не в списке', missingLabel: 'регион', points: regionPoints },
    { label: 'Страны маршрутов различаются', missingLabel: 'маршрут Cloudflare', points: routePoints },
    { label: 'WebRTC отличается', missingLabel: 'WebRTC', points: rtcPoints },
    { label: dnsLabel, missingLabel: 'DNS', points: dnsPoints },
  ];
}
export function serviceAssessment(service, result, network = []) {
  if (!result?.trace) return result?.attempted ? { tone: 'bad', label: 'Нет доступа' } : { tone: '', label: 'Не проверено' };
  const country = serviceCountry(result);
  const status = regionStatus(service, country);
  if (status === 'unlisted') return { tone: 'bad', label: 'Регион не в списке' };
  const risk = result.risk;
  if (!risk || status === 'unknown' || reputation(risk) === null) return { tone: 'warn', label: 'Нужны данные' };
  if (risk.dataStale) return { tone: 'warn', label: 'Базы устарели' };
  if (network.some(factor => factor.points > 0)) return { tone: 'warn', label: 'Есть расхождения' };
  const flags = ['is_vpn', 'is_proxy', 'is_tor', 'is_abuser', 'is_datacenter', 'is_abuse_asn'];
  if (risk.scoreSource !== 'netcoffee') flags.push('is_proxy_block');
  if (flags.some(key => risk[key] === true)) return { tone: 'warn', label: 'Есть совпадения' };
  const incomplete = risk.scoreSource === 'netcoffee' ? ['is_vpn', 'is_proxy', 'is_tor', 'is_abuser', 'is_datacenter'].some(key => typeof risk[key] !== 'boolean') : scoreFactors(risk).some(factor => factor.points === null);
  if (incomplete || network.some(factor => factor.points === null)) return { tone: 'warn', label: 'Данные неполные' };
  return { tone: 'good', label: 'Совпадений не найдено' };
}
export function describeCountry(service, code) {
  const name = countryName(code);
  const status = regionStatus(service, code);
  if (!name || status === 'unknown') return { name: 'Не удалось определить', status: 'unknown' };
  return { name, status };
}
export function maskIP(ip) {
  if (!ip) return '—';
  if (ip.includes(':')) return `${ip.split(':').slice(0, 2).join(':')}:••••`;
  const parts = ip.split('.');
  return parts.length === 4 ? `${parts[0]}.${parts[1]}.•••.•••` : '••••';
}
export function formatFlag(value) {
  return value === true ? 'Найдено совпадение' : value === false ? 'В списке не найдено' : 'Нет данных';
}
