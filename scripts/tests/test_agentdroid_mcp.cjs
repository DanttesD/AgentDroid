const assert=require('assert'),fs=require('fs'),os=require('os'),path=require('path');
const {apply}=require(path.join(__dirname,'../../app/src/main/assets/agentdroid-mcp.cjs'));
const t=fs.mkdtempSync(path.join(os.tmpdir(),'mcp-')),desc=path.join(t,'d.json'),root=path.join(t,'root');
assert.strictEqual(apply(root,desc),'skipped:no descriptor');
fs.writeFileSync(desc,JSON.stringify({url:'http://127.0.0.1:8765/mcp/abc123',environment:'You run on a phone.'}));
assert.strictEqual(apply(root,desc),'wired');
const cfgFile=path.join(root,'config/opencode/opencode.json');
let c=JSON.parse(fs.readFileSync(cfgFile));
assert.strictEqual(c.mcp.agentdroid.url,'http://127.0.0.1:8765/mcp/abc123');assert.strictEqual(c.mcp.agentdroid.type,'remote');
assert.ok(c.instructions[0].endsWith('agentdroid-environment.md'));
assert.ok(fs.readFileSync(path.join(root,'config/opencode/agentdroid-environment.md'),'utf8').includes('phone'));
assert.strictEqual(apply(root,desc),'unchanged');
// user's own settings survive; token rotation updates
c.model='openrouter/x';c.mcp.other={type:'local',command:['x']};c.instructions.push('/my.md');fs.writeFileSync(cfgFile,JSON.stringify(c));
fs.writeFileSync(desc,JSON.stringify({url:'http://127.0.0.1:8765/mcp/new456',environment:'x'}));
assert.strictEqual(apply(root,desc),'wired');
c=JSON.parse(fs.readFileSync(cfgFile));
assert.strictEqual(c.model,'openrouter/x');assert.ok(c.mcp.other);assert.ok(c.instructions.includes('/my.md'));assert.strictEqual(c.mcp.agentdroid.url,'http://127.0.0.1:8765/mcp/new456');assert.strictEqual(c.instructions.filter(x=>x.endsWith('agentdroid-environment.md')).length,1);
// broken config is never overwritten; bad url refused
fs.writeFileSync(cfgFile,'{oops');assert.ok(apply(root,desc).startsWith('skipped:config'));assert.strictEqual(fs.readFileSync(cfgFile,'utf8'),'{oops');
fs.writeFileSync(desc,JSON.stringify({url:'https://evil.example/mcp/x'}));assert.ok(apply(root,desc).startsWith('skipped'));
console.log('ok');
