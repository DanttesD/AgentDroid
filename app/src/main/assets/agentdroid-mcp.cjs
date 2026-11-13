'use strict';
// Wires OpenCode to the AgentDroid MCP server of this phone: adds mcp.agentdroid and an instructions file to the
// connection's own OpenCode config. Never throws and never touches keys it does not own.
const fs = require('fs');
const path = require('path');

function readJson(file) {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch (e) { return null; }
}

function write(file, text) {
  const tmp = file + '.' + process.pid + '.tmp';
  fs.writeFileSync(tmp, text, { mode: 0o600 });
  fs.renameSync(tmp, file);
}

/**
 * @param {string} root connection root (XDG_CONFIG_HOME is root/config)
 * @param {string} descriptor path to agentdroid-mcp.json written by the app: {url, environment}
 * @returns {string} what happened: 'wired' | 'unchanged' | 'skipped:<why>'
 */
function apply(root, descriptor) {
  try {
    const d = readJson(descriptor);
    if (!d || typeof d.url !== 'string' || !/^http:\/\/127\.0\.0\.1:\d{1,5}\/mcp\/[A-Za-z0-9_-]+$/.test(d.url)) return 'skipped:no descriptor';
    const dir = path.join(root, 'config', 'opencode');
    fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
    const cfgFile = path.join(dir, 'opencode.json');
    const envFile = path.join(dir, 'agentdroid-environment.md');
    let cfg = {};
    if (fs.existsSync(cfgFile)) {
      cfg = readJson(cfgFile);
      if (!cfg || typeof cfg !== 'object' || Array.isArray(cfg)) return 'skipped:config is not valid JSON';
    }
    const before = JSON.stringify(cfg) + (fs.existsSync(envFile) ? fs.readFileSync(envFile, 'utf8') : '');
    if (!cfg.$schema) cfg.$schema = 'https://opencode.ai/config.json';
    if (!cfg.mcp || typeof cfg.mcp !== 'object') cfg.mcp = {};
    cfg.mcp.agentdroid = { type: 'remote', url: d.url, enabled: true, oauth: false };
    const list = Array.isArray(cfg.instructions) ? cfg.instructions.filter(x => typeof x === 'string') : [];
    if (!list.includes(envFile)) list.push(envFile);
    cfg.instructions = list;
    const env = typeof d.environment === 'string' ? d.environment.slice(0, 8000) : '';
    const text = '# AgentDroid environment\n\n' + env + '\n';
    if (JSON.stringify(cfg) + text === before) return 'unchanged';
    write(envFile, text);
    write(cfgFile, JSON.stringify(cfg, null, 2) + '\n');
    return 'wired';
  } catch (e) {
    return 'skipped:' + (e && e.message);
  }
}

module.exports = { apply };
