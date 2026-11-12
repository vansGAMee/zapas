import { chromium } from 'playwright';
import AxeBuilder from '@axe-core/playwright';
import { spawn } from 'node:child_process';
import fs from 'node:fs/promises';
import assert from 'node:assert/strict';

const artifacts=new URL('../artifacts/',import.meta.url).pathname;
await fs.mkdir(artifacts,{recursive:true});
const browser=await chromium.launch({executablePath:process.env.CHROMIUM||'/usr/bin/chromium',headless:true,args:['--disable-dev-shm-usage','--renderer-process-limit=1']});
const results=[];
async function server(seed=false){
  const proc=spawn(process.env.ZAPAS_PYTHON||'.venv/bin/python',['tests/serve.py','--fake-account',...(seed?['--seed']:[])],{stdio:['ignore','pipe','pipe']});
  let stderr='';proc.stderr.on('data',d=>stderr+=d);
  const url=await new Promise((resolve,reject)=>{let text='';const timeout=setTimeout(()=>reject(new Error('Server timeout: '+stderr)),25000);proc.stdout.on('data',d=>{text+=d;const m=text.match(/READY (http[^\s]+)/);if(m){clearTimeout(timeout);resolve(m[1]);}});proc.on('exit',c=>{clearTimeout(timeout);reject(new Error('Server exited '+c+': '+stderr));});});
  return {proc,url,stop:()=>new Promise(resolve=>{proc.once('exit',resolve);proc.kill('SIGTERM');})};
}
async function audit(page,name){const report=await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21aa']).analyze();results.push({name,violations:report.violations.map(v=>({id:v.id,impact:v.impact,nodes:v.nodes.map(n=>n.target)}))});await fs.writeFile(artifacts+'accessibility.json',JSON.stringify(results,null,2));assert.equal(report.violations.length,0,`${name}: ${JSON.stringify(results.at(-1).violations)}`);}
try{
  let s=await server();let context=await browser.newContext({viewport:{width:1440,height:1000}});let page=await context.newPage();const errors=[];page.on('pageerror',e=>errors.push(e.message));
  try{
    await page.goto(s.url);await page.getByRole('heading',{name:'Твой YouTube.'}).waitFor();
    await page.screenshot({path:artifacts+'desktop-empty.png',fullPage:true});await audit(page,'empty');
    await page.locator('main').getByRole('button',{name:'Подключить YouTube',exact:true}).first().click();
    await page.locator('.video-card').first().waitFor();
    assert.equal(await page.locator('.video-card').count(),2);
    await page.getByRole('button',{name:'Загрузки'}).click();await page.getByText('В очереди',{exact:true}).first().waitFor();
    await page.getByRole('button',{name:'Настройки',exact:true}).click();await page.getByLabel('Сколько видео готовить').fill('25');await page.getByLabel('Сколько видео готовить').blur();
    await page.getByRole('button',{name:'Экономный',exact:false}).click();await page.waitForTimeout(300);
    await page.screenshot({path:artifacts+'desktop-settings.png',fullPage:true});await audit(page,'settings');
    await page.getByRole('button',{name:'Телефон',exact:true}).click();await page.getByRole('button',{name:'Подключить телефон',exact:true}).first().click();
    await page.getByRole('heading',{name:'Подключить устройство'}).waitFor();assert.match(await page.locator('.pair-code').innerText(),/^\d{6}$/);
    await page.getByRole('button',{name:'Готово',exact:true}).click();assert.deepEqual(errors,[]);
  }finally{await context.close();await s.stop();}
  s=await server(true);context=await browser.newContext({viewport:{width:1440,height:1000}});page=await context.newPage();
  try{
    await page.goto(s.url);await page.locator('.video-card').first().waitFor();assert.equal(await page.locator('.video-card').count(),6);
    await page.screenshot({path:artifacts+'desktop-library.png',fullPage:true});await audit(page,'library');
    await page.getByLabel('Поиск в медиатеке').fill('Лес, тишина');assert.equal(await page.locator('.video-card').count(),1);await page.getByLabel('Поиск в медиатеке').fill('');
    await page.getByRole('button',{name:'Закреплённые',exact:true}).click();assert.equal(await page.locator('.video-card').count(),1);
    await page.getByRole('button',{name:'Все видео',exact:true}).click();
    await page.locator('.thumbnail').first().click();await page.locator('video').waitFor();
    await page.locator('video').evaluate(v=>{v.pause();v.currentTime=.6;});await page.waitForFunction(()=>document.querySelector('video').readyState>=2);
    await page.screenshot({path:artifacts+'desktop-player.png',fullPage:true});
    await page.getByRole('button',{name:'Медиатека',exact:true}).first().click();
    await page.setViewportSize({width:412,height:915});await page.screenshot({path:artifacts+'desktop-compact.png',fullPage:true});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);await audit(page,'compact');
  }finally{await context.close();await s.stop();}
  await fs.writeFile(artifacts+'browser-result.json',JSON.stringify({passed:true,checks:['empty state','automatic recommendations after account connection','queue','settings persistence','pairing','feed','search','pinned filter','actual H.264 playback','compact layout'],accessibility:results},null,2));
  console.log('Browser flows and accessibility checks passed.');
}finally{await browser.close();}
