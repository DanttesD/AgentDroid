// Runs the page without its Node server: the three /api/* routes are answered inside the page.
(() => {
  const realFetch = window.fetch.bind(window);
  const DNSLEAK = 'https://dnsleak.dev';
  const boolOrNull = v => typeof v === 'boolean' ? v : null;
  const ipVersion = ip => String(ip).includes(':') ? 6 : /^\d+(\.\d+){3}$/.test(String(ip)) ? 4 : null;
  const asnCategory = value => {
    const kind = String(value || '').toLowerCase();
    if (['hosting', 'isp', 'business', 'education_research', 'government_admin'].includes(kind)) return kind;
    if (kind === 'education' || kind === 'research') return 'education_research';
    if (kind === 'government') return 'government_admin';
    return null;
  };
  const reply = (value, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
  function fromNetCoffee(data, ip) {
    const country = String(data.countryCode || '').toUpperCase();
    const geo = /^[A-Z]{2}$/.test(country) ? country : null;
    const hosting = boolOrNull(data.is_datacenter);
    const trust = Number(data.trust_score);
    const score = Number.isInteger(trust) && trust >= 0 && trust <= 100 ? trust : null;
    const fields = ['is_vpn', 'is_proxy', 'is_tor', 'is_datacenter', 'is_abuser', 'is_crawler', 'is_mobile', 'asn', 'asOrganization', 'company_name', 'geoCountryCode', 'city', 'region'];
    const asn = Number(data.asn);
    return {
      ip, asn: Number.isInteger(asn) && asn > 0 ? asn : null,
      asOrganization: data.asOrganization || data.company_name || null,
      asnCategory: asnCategory(data.asn_kind || data.company_type) || (hosting === true ? 'hosting' : hosting === false ? 'isp' : null),
      is_hosting_asn: hosting === true, city: data.city || null, region: data.region || null, geoCountryCode: geo,
      ipVersion: ipVersion(ip), is_vpn: boolOrNull(data.is_vpn), is_proxy: boolOrNull(data.is_proxy), is_proxy_block: false,
      is_tor: boolOrNull(data.is_tor), is_datacenter: hosting, is_abuser: boolOrNull(data.is_abuser), is_abuse_asn: false,
      sourceUpdatedAt: new Date().toISOString(), dataStale: false, abuseSourceBuilt: null,
      is_crawler: boolOrNull(data.is_crawler), is_mobile: boolOrNull(data.is_mobile), company_name: data.company_name || null,
      netcoffeeScore: score, netcoffeeCidr: data.cidr || null, netcoffeeCountryCode: geo,
      scoreSource: score === null ? 'local' : 'netcoffee', providerAvailable: score !== null,
      fieldSources: Object.fromEntries(fields.map(f => [f, 'netcoffee']))
    };
  }
  function unknown(ip) {
    return { ip, asn: null, asOrganization: null, asnCategory: null, is_hosting_asn: null, city: null, region: null, geoCountryCode: null,
      ipVersion: ipVersion(ip), is_vpn: null, is_proxy: null, is_proxy_block: false, is_tor: null, is_datacenter: null, is_abuser: null,
      is_abuse_asn: false, sourceUpdatedAt: new Date().toISOString(), dataStale: false, abuseSourceBuilt: null, is_crawler: null, is_mobile: null,
      company_name: null, netcoffeeScore: null, netcoffeeCidr: null, netcoffeeCountryCode: null, scoreSource: 'local', providerAvailable: false, fieldSources: {} };
  }
  async function getJson(url, ms) {
    try {
      const r = await realFetch(url, { headers: { Accept: 'application/json' }, signal: AbortSignal.timeout(ms), credentials: 'omit' });
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return await r.json();
    } catch (e) {
      // A native request is the fallback when the service forbids cross-origin reads.
      if (window.AgentNative && url.startsWith(DNSLEAK)) {
        const text = window.AgentNative.get(url);
        if (text) return JSON.parse(text);
      }
      throw e;
    }
  }
  const randomId = () => Array.from(crypto.getRandomValues(new Uint8Array(10)), b => 'abcdefghijklmnopqrstuvwxyz0123456789'[b % 36]).join('');
  const svcByIp = {};
  const report = (r, ip) => {
    try {
      if (window.AgentNative && window.AgentNative.report) window.AgentNative.report(JSON.stringify({ score: r.netcoffeeScore, cc: r.netcoffeeCountryCode, org: r.asOrganization || r.company_name || '', service: svcByIp[ip] || '', hosting: r.is_datacenter === true, vpn: r.is_vpn === true, proxy: r.is_proxy === true }));
    } catch { /* the page works without the app */ }
    return r;
  };
  async function reputation(body) {
    const ip = String(body?.ip || '');
    const nc = body?.netcoffee;
    if (nc && typeof nc === 'object' && Number.isInteger(Number(nc.trust_score))) return report(fromNetCoffee(nc, ip), ip);
    try {
      const data = await getJson('https://ip.net.coffee/api/iprisk/' + encodeURIComponent(ip), 6000);
      if (data && typeof data === 'object') return report(fromNetCoffee(data, ip), ip);
    } catch { /* unknown below */ }
    return report(unknown(ip), ip);
  }
  async function dnsStart() {
    let session = null;
    try { session = await getJson(DNSLEAK + '/api/session', 6000); } catch { /* local id */ }
    const testId = /^[a-z0-9]{10}$/.test(String(session?.testId || '')) ? session.testId : randomId();
    const zone = typeof session?.zone === 'string' && session.zone.endsWith('dnsleak.dev') ? session.zone : 't.dnsleak.dev';
    const hostnames = [];
    for (let i = 1; i <= 6; i++) for (const p of ['a', 'q']) hostnames.push(`${testId}-${p}${String(i).padStart(2, '0')}.${zone}`);
    return { testId, hostnames };
  }
  async function dnsResult(testId) {
    try {
      const report = await getJson(DNSLEAK + '/api/result/' + encodeURIComponent(testId), 7000);
      const resolvers = Array.isArray(report?.resolvers) ? report.resolvers.map(r => ({ ...r, organization: r.org || r.operator || null })) : [];
      const status = report?.verdict?.status;
      return { testId, client: report?.client || null, resolvers, lookups: Number(report?.lookups) || 0, truncated: Boolean(report?.truncated),
        verdict: status === 'leak' ? 'split' : (status || 'unknown'), detail: report?.verdict?.detail || null };
    } catch { return { testId, resolvers: [], lookups: 0, truncated: false, verdict: 'unavailable' }; }
  }
  window.fetch = async (input, init) => {
    let u;
    try { u = new URL(typeof input === 'string' ? input : input.url, location.href); } catch { return realFetch(input, init); }
    if (/\/cdn-cgi\/trace$/.test(u.pathname) && u.origin !== location.origin) {
      const res = await realFetch(input, init);
      const key = u.hostname.includes('chatgpt') ? 'chatgpt' : u.hostname.includes('claude') ? 'claude' : '';
      if (key) res.clone().text().then(t => { const m = /^ip=(.+)$/m.exec(t); if (m) svcByIp[m[1].trim()] = key; }).catch(() => { });
      return res;
    }
    if (u.origin !== location.origin || !u.pathname.startsWith('/api/')) return realFetch(input, init);
    try {
      if (u.pathname === '/api/reputation') return reply(await reputation(JSON.parse(init?.body || '{}')));
      if (u.pathname === '/api/dns/start') return reply(await dnsStart());
      if (u.pathname.startsWith('/api/dns/result/')) return reply(await dnsResult(u.pathname.split('/').pop()));
    } catch { /* falls through */ }
    return reply({ error: 'unavailable' }, 404);
  };
})();
