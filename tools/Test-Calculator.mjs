import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import assert from 'node:assert/strict';

const html = readFileSync(new URL('../src/main/assets/calculator.html', import.meta.url), 'utf8');
const elements = { display: { textContent: '0' }, keys: { children: [], append(button) { this.children.push(button); } }, send: {} };
const messages = [];
runInNewContext(html.match(/<script>([\s\S]*?)<\/script>/)[1], {
  document: { getElementById: id => elements[id], createElement: () => ({}) },
  window: { Kigtts: true }, Kigtts: { postMessage: data => messages.push(JSON.parse(data)) },
});
function press(...keys) { for (const key of keys) elements.keys.children.find(button => button.textContent === key).onclick(); }
press('8', '+', '2', '='); assert.equal(elements.display.textContent, '10');
press('C', '.', '1', '+', '0', '.', '2', '='); assert.equal(elements.display.textContent, '0.3');
elements.send.onclick(); assert.deepEqual(messages[0], { action: 'calculator/result', inputs: { result: '0.3' } });
press('C', '1', '2', '⌫'); assert.equal(elements.display.textContent, '1');
press('C', '1', '÷', '0', '='); assert.equal(elements.display.textContent, '无法计算');
press('C', '5', '±', '%'); assert.equal(elements.display.textContent, '-0.05');
console.log('Calculator arithmetic and subtitle messages passed.');
