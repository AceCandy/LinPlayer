// 执行页面实际 API 入口,验证取消确认时三个凭据入口都不会发请求。
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const html = fs.readFileSync(new URL('../core/companion/page.html', import.meta.url), 'utf8');
const source = html.slice(html.indexOf('const BASE ='), html.indexOf('const $ ='));
const commands = ['emby.login', 'account.batchParse', 'account.batchAddServers'];
function page(accept, protocol = 'http:') {
  const state = { sent: 0, asked: 0 };
  const ctx = vm.createContext({
    location: { pathname: '/pair', protocol },
    confirm: message => { assert.match(message, /明文 HTTP/); state.asked++; return accept; },
    fetch: async () => { state.sent++; return { json: async () => ({ data: true }) }; },
  });
  vm.runInContext(source, ctx);
  return { state, api: (command) => vm.runInContext(`api(${JSON.stringify(command)}, {})`, ctx) };
}
for (const command of commands) {
  const p = page(false);
  await assert.rejects(p.api(command), /账号信息未发送/);
  assert.equal(p.state.sent, 0);
  assert.equal(p.state.asked, 1);
}
const accepted = page(true);
for (const command of commands) await accepted.api(command);
assert.equal(accepted.state.sent, 3);
assert.equal(accepted.state.asked, 1);
const remote = page(false);
await remote.api('player.setPause');
assert.equal(remote.state.asked, 0);
assert.equal(remote.state.sent, 1);
console.log('局域网风险确认:登录、批量解析、批量添加及遥控兼容性检查通过');
