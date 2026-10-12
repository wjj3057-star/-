// Production UI with mock Android storage/device/SMS; never uses real contacts or messages.
const {chromium}=require('playwright');
const assert=require('node:assert/strict'),http=require('node:http'),fs=require('node:fs'),path=require('node:path');
const C=require('../app/src/main/assets/core.js'),assets=path.resolve(__dirname,'../app/src/main/assets');
const server=http.createServer((req,res)=>{
  const name=new URL(req.url,'http://localhost').pathname,font=name.startsWith('/qa-font/')&&process.env.QA_FONT_DIR;
  const base=font?path.resolve(process.env.QA_FONT_DIR):assets,file=path.resolve(base,font?name.slice(9):name==='/'?'index.html':'.'+name);
  if(!file.startsWith(base+path.sep)||!fs.existsSync(file)||fs.statSync(file).isDirectory()){res.writeHead(404);res.end();return;}
  res.setHeader('Content-Type',file.endsWith('.css')?'text/css':file.endsWith('.js')?'application/javascript':file.endsWith('.woff2')?'font/woff2':'text/html; charset=utf-8');res.end(fs.readFileSync(file));
});
async function main(){
  await new Promise(r=>server.listen(0,'127.0.0.1',r));const url=`http://127.0.0.1:${server.address().port}`;
  const browser=await chromium.launch({headless:true,...(process.env.CHROMIUM_PATH?{executablePath:process.env.CHROMIUM_PATH}:{}),args:JSON.parse(process.env.CHROMIUM_ARGS_JSON||'[]')});
  const page=await browser.newPage({viewport:{width:390,height:844},locale:'ko-KR',timezoneId:'Asia/Seoul',colorScheme:'light',bypassCSP:!!process.env.QA_FONT_DIR}),errors=[];
  page.on('pageerror',e=>errors.push(e.message));const seed=C.fresh();delete seed.settings.theme;
  seed.students=[{id:'s1',name:'김하늘',classId:C.DEFAULT_CLASS_ID,memo:'',phone:'',contactId:'',guardians:[{id:'g1',name:'김하늘 어머니',phone:'01011112222',relation:'mother',notify:true,contactId:''}],active:true,joinedDate:'2026-01-01',archivedDate:null}];
  await page.addInitScript(seed=>{
    if(!localStorage.getItem('theme-test'))localStorage.setItem('theme-test',JSON.stringify(seed));
    window.mockDevice={systemDark:true,contactsGranted:true,smsGranted:true,smsCapable:true,simReady:true};window.mockFails=false;window.mockCalls=[];window.mockLogs=[];
    window.NativeAttendance={
      readState:()=>JSON.stringify({ok:true,data:localStorage.getItem('theme-test')}),
      saveState:raw=>{if(window.mockFails)return JSON.stringify({ok:false,error:'저장 공간이 부족해요.'});localStorage.setItem('theme-test',raw);return JSON.stringify({ok:true});},
      deviceInfo:()=>JSON.stringify(window.mockDevice),smsLogs:()=>JSON.stringify({ok:true,jobs:window.mockLogs}),
      sendAttendanceSms:raw=>window.mockCalls.push(JSON.parse(raw)),retrySms:()=>{},requestSmsPermission:()=>{},openAppSettings:()=>{}
    };
  },seed);
  await page.clock.install({time:new Date('2026-10-12T07:00:00Z')});await page.goto(url);
  const theme=()=>page.locator('html').getAttribute('data-theme'),read=()=>page.evaluate(()=>JSON.parse(localStorage.getItem('theme-test')));
  const tab=t=>page.locator(`[data-tab=${t}]`).click(),action=a=>page.locator(`[data-contact-action=${a}]`),close=()=>page.locator('.modal-header [data-action=close]').click();
  assert.equal(await theme(),'dark','native system mode overrides WebView media query');
  await tab('settings');await page.locator('[data-action=theme-edit]').click();
  await page.locator('[name=theme][value=light]').check();assert.equal(await theme(),'light');assert.equal((await read()).settings.theme,'light');
  await page.evaluate(()=>{window.mockDevice.systemDark=false;window.AttendanceApp.onResume();});
  await page.locator('[name=theme][value=dark]').check();assert.equal(await theme(),'dark');await close();await page.reload();assert.equal(await theme(),'dark');
  await tab('settings');await page.locator('[data-action=theme-edit]').click();await page.evaluate(()=>window.mockFails=true);
  await page.locator('[name=theme][value=light]').click();assert.equal(await page.locator('[name=theme][value=dark]').isChecked(),true);assert.equal(await theme(),'dark');assert.equal((await read()).settings.theme,'dark');assert.equal(await page.locator('#form-error').isVisible(),true);
  await page.evaluate(()=>window.mockFails=false);await page.locator('[name=theme][value=system]').check();await close();
  await action('sms-settings').click();await page.locator('#sms-template').fill('{작성 중인 문구}');
  await page.evaluate(()=>{window.mockDevice.systemDark=false;window.AttendanceApp.onResume();});assert.equal(await theme(),'light');assert.equal(await page.locator('#sms-template').inputValue(),'{작성 중인 문구}');
  await page.evaluate(()=>{window.mockDevice.systemDark=true;window.AttendanceApp.onResume();});assert.equal(await theme(),'dark');
  // Immediate status saves never commit invalid template drafts or send messages.
  await page.locator('[name=statuses][value=absent]').check();assert.ok((await read()).settings.sms.statuses.includes('absent'));assert.equal((await read()).settings.sms.template,C.DEFAULT_TEMPLATE);
  await page.locator('[name=enabled]').check();await close();await page.reload();await tab('settings');await action('sms-settings').click();
  assert.equal(await page.locator('[name=statuses][value=absent]').isChecked(),true);assert.equal(await page.locator('[name=enabled]').isChecked(),true);
  await page.evaluate(()=>window.mockFails=true);await page.locator('[name=statuses][value=absent]').click();assert.equal(await page.locator('[name=statuses][value=absent]').isChecked(),true);assert.ok((await read()).settings.sms.statuses.includes('absent'));
  await page.evaluate(()=>window.mockFails=false);for(const box of await page.locator('[name=statuses]').all())await box.uncheck();
  assert.deepEqual((await read()).settings.sms.statuses,[]);assert.equal((await read()).settings.sms.enabled,false);assert.equal(await page.locator('[name=enabled]').isChecked(),false);
  await page.locator('[name=statuses][value=present]').check();await page.locator('[name=enabled]').check();await close();
  await tab('students');await page.locator('#archived-toggle').check();await page.reload();await tab('students');assert.equal(await page.locator('#archived-toggle').isChecked(),true);
  await page.evaluate(()=>window.mockFails=true);await page.locator('#archived-toggle').click();assert.equal(await page.locator('#archived-toggle').isChecked(),true);await page.evaluate(()=>window.mockFails=false);
  // Existing guardian opt-out is durable without saving the rest of the student form.
  await tab('students');await page.locator('[data-action=student-detail]').first().click();await page.locator('[data-action=student-edit]').click();
  await page.locator('[name=name]').fill('저장하지 않은 이름');await page.locator('[name=g-notify-0]').uncheck();
  assert.equal((await read()).students[0].guardians[0].notify,false);assert.equal((await read()).students[0].name,'김하늘');assert.equal(await page.locator('[name=name]').inputValue(),'저장하지 않은 이름');
  await page.evaluate(()=>window.mockFails=true);await page.locator('[name=g-notify-0]').click();assert.equal(await page.locator('[name=g-notify-0]').isChecked(),false);assert.equal((await read()).students[0].guardians[0].notify,false);
  await page.evaluate(()=>window.mockFails=false);await close();await page.reload();await tab('students');await page.locator('[data-action=student-detail]').first().click();await page.locator('[data-action=student-edit]').click();assert.equal(await page.locator('[name=g-notify-0]').isChecked(),false);await close();
  // Reading a result or choosing settings must never itself transmit.
  assert.deepEqual(await page.evaluate(()=>window.mockCalls),[]);
  await tab('settings');await page.evaluate(()=>window.mockLogs=[{id:'job1',studentName:'김하늘',attendanceStatus:'present',guardianName:'김하늘 어머니',phone:'01011112222',createdAt:Date.now(),date:'2026-10-12',parts:2,sentParts:1,failedParts:0,pendingParts:1,message:'테스트 출석 안내',state:'sending',error:''}]);await action('sms-log').click();assert.equal(await page.locator('.sms-state').textContent(),'결과 대기');
  await page.evaluate(()=>{window.savedCard=document.querySelector('.sms-log-card');});await page.clock.runFor(3100);assert.equal(await page.evaluate(()=>window.savedCard===document.querySelector('.sms-log-card')),true,'unchanged logs preserve DOM/focus');
  await page.evaluate(()=>{Object.assign(window.mockLogs[0],{state:'sent',sentParts:2,pendingParts:0});window.AttendanceApp.onResume();});assert.equal(await page.locator('.sms-state').textContent(),'발송 완료');assert.equal(await action('retry').count(),0);
  await page.evaluate(()=>{Object.assign(window.mockLogs[0],{state:'partial',sentParts:1,failedParts:1,error:'일부 전송 오류'});window.AttendanceApp.onResume();});assert.equal(await page.locator('.sms-state').textContent(),'일부 발송 실패');assert.equal(await action('retry').count(),1);await close();
  // Mobile widths and four primary screens keep their dark surface, without overflow.
  for(const width of [320,390,768]){await page.setViewportSize({width,height:844});for(const t of ['attendance','students','reports','settings']){await tab(t);assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false,`${width} ${t} width`);assert.equal(await theme(),'dark');}}
  await page.setViewportSize({width:390,height:844});
  if(process.env.SCREENSHOT_DIR){if(process.env.QA_FONT_DIR){await page.route('**/qa-font/**',route=>route.continue());await page.addStyleTag({url:url+'/qa-font/400.css'});await page.addStyleTag({url:url+'/qa-font/700.css'});await page.evaluate(()=>document.fonts.ready);}fs.mkdirSync(process.env.SCREENSHOT_DIR,{recursive:true});await tab('attendance');await page.screenshot({path:path.join(process.env.SCREENSHOT_DIR,'attendance-dark.png'),fullPage:true});await tab('settings');await action('sms-settings').click();await page.screenshot({path:path.join(process.env.SCREENSHOT_DIR,'sms-settings-dark.png')});await close();await page.locator('[data-action=theme-edit]').click();await page.screenshot({path:path.join(process.env.SCREENSHOT_DIR,'theme-dark.png')});await close();}
  // Plain browser falls back to its live media query, including changes while editing.
  const web=await browser.newPage({colorScheme:'light'});await web.goto(url);assert.equal(await web.locator('html').getAttribute('data-theme'),'light');await web.emulateMedia({colorScheme:'dark'});await web.waitForFunction(()=>document.documentElement.dataset.theme==='dark');await web.close();
  assert.deepEqual(errors,[]);console.log('Theme/autosave UI passed: native/system/manual theme, restart, live draft retention, checkbox persistence/failure rollback, guardian opt-out, multipart logs/refresh, browser media query and 320/390/768px layouts.');
  await browser.close();server.close();
}
main().catch(e=>{console.error(e);server.close();process.exit(1);});
