// Run after installing Playwright and its Chromium browser.
// Optional: CHROMIUM_PATH, CHROMIUM_ARGS_JSON, QA_SCREENSHOT_DIR, QA_FONT_DIR.
const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const http=require('node:http');
const fs=require('node:fs');
const path=require('node:path');
const C=require('../app/src/main/assets/core.js');
const assets=path.resolve(__dirname,'../app/src/main/assets');
const failures=[];
const server=http.createServer((req,res)=>{
  const pathname=new URL(req.url,'http://localhost').pathname;
  const font=pathname.startsWith('/qa-font/')&&process.env.QA_FONT_DIR;
  const base=font?path.resolve(process.env.QA_FONT_DIR):assets;
  const file=path.resolve(base,font?pathname.slice(9):pathname==='/'?'index.html':'.'+pathname);
  if(!file.startsWith(base+path.sep)||!fs.existsSync(file)||fs.statSync(file).isDirectory()){res.writeHead(404);res.end();return;}
  res.setHeader('Content-Type',file.endsWith('.css')?'text/css':file.endsWith('.js')?'application/javascript':file.endsWith('.woff2')?'font/woff2':'text/html; charset=utf-8');
  res.end(fs.readFileSync(file));
});
async function main(){
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const url=`http://127.0.0.1:${server.address().port}`;
  const browser=await chromium.launch({headless:true,...(process.env.CHROMIUM_PATH?{executablePath:process.env.CHROMIUM_PATH}:{}),args:JSON.parse(process.env.CHROMIUM_ARGS_JSON||'[]')});
  const context=await browser.newContext({viewport:{width:390,height:844},deviceScaleFactor:1,isMobile:true,hasTouch:true,locale:'ko-KR',timezoneId:'Asia/Seoul',acceptDownloads:true});
  const page=await context.newPage();page.on('pageerror',error=>failures.push(error.message));
  await page.clock.install({time:new Date('2026-10-08T07:30:00Z')});
  const click=async selector=>{await page.locator(selector).click();};
  const read=()=>page.evaluate(()=>JSON.parse(localStorage.getItem('oneul-attendance-v1')));
  await page.goto(url);await page.getByRole('heading',{name:'출석 체크',exact:true}).waitFor();
  await page.getByRole('button',{name:'학생 등록하기',exact:true}).click();
  assert.equal(await page.locator('[name=classId]').count(),0);
  await page.locator('[name=name]').fill('김하늘');
  await page.getByRole('button',{name:'학생 등록',exact:true}).click();
  await page.locator('.student-card').first().waitFor();
  await click('[data-action=mark][data-status=present]');
  assert.equal((await read()).records[0].status,'present');
  await page.reload();await page.locator('.status-button.present.selected').waitFor();
  await click('[data-action=record-note]');await page.locator('[name=status]').selectOption('late');await page.locator('[name=note]').fill('병원 진료 후 등원');await page.getByRole('button',{name:'메모 저장',exact:true}).click();
  assert.equal((await read()).records[0].note,'병원 진료 후 등원');
  await click('[data-action=student-add]');await page.locator('[name=name]').fill('박지우');await page.getByRole('button',{name:'학생 등록',exact:true}).click();
  await click('[data-action=bulk-present]');await click('#confirm-action');
  let state=await read();assert.equal(state.records.length,2);assert.equal(state.records.find(r=>r.studentName==='김하늘').status,'late');assert.equal(state.records.find(r=>r.studentName==='박지우').status,'present');
  await click('[data-action=undo]');assert.equal((await read()).records.length,1);
  await click('[data-action=bulk-present]');await click('#confirm-action');
  await page.locator('#attendance-search').fill('지우');assert.equal(await page.locator('.student-card').count(),1);await page.locator('#attendance-search').fill('');
  await click('[data-action=tab][data-tab=reports]');await page.getByText('100%',{exact:true}).waitFor();
  const csvPromise=page.waitForEvent('download');await click('[data-action=export-month].icon-button');const csv=await csvPromise;const csvText=fs.readFileSync(await csv.path(),'utf8');assert.ok(csvText.includes('김하늘'));assert.ok(csvText.includes('지각'));assert.ok(csvText.startsWith('\uFEFF'));
  await click('[data-action=tab][data-tab=settings]');const backupPromise=page.waitForEvent('download');await click('[data-action=backup]');const backup=await backupPromise;const backupRaw=fs.readFileSync(await backup.path(),'utf8');assert.equal(C.parseBackup(backupRaw).students.length,2);
  await page.locator('#import-file').setInputFiles({name:'broken.json',mimeType:'application/json',buffer:Buffer.from('{broken')});await page.getByText('읽을 수 없는 백업 파일입니다.',{exact:true}).waitFor();assert.equal((await read()).students.length,2);
  await click('[data-action=tab][data-tab=students]');await page.locator('[data-action=student-detail]').filter({hasText:'김하늘'}).click();await click('[data-action=student-archive]');await click('#confirm-action');assert.equal((await read()).students.find(s=>s.name==='김하늘').active,false);assert.equal((await read()).records.length,2);
  await page.locator('#import-file').setInputFiles({name:'backup.json',mimeType:'application/json',buffer:Buffer.from(backupRaw)});assert.equal(await page.locator('#restore-confirm').isDisabled(),true);await page.locator('#restore-consent').check();await click('#restore-confirm');assert.equal((await read()).students.find(s=>s.name==='김하늘').active,true);
  await click('[data-action=tab][data-tab=attendance]');
  await page.locator('#attendance-date').fill('2026-10-09');await page.locator('#attendance-date').dispatchEvent('change');assert.equal(await page.locator('[data-action=mark]:not(:disabled)').count(),0);await click('[data-action=today]');
  // Literal names from backups must never become DOM markup.
  const injected=await read();injected.students[0].name='<svg/onload=alert(1)>';await page.evaluate(s=>localStorage.setItem('oneul-attendance-v1',JSON.stringify(s)),injected);await page.reload();await click('[data-action=tab][data-tab=students]');assert.equal(await page.locator('svg[onload]').count(),0);await page.getByRole('heading',{name:'<svg/onload=alert(1)>',exact:true}).waitFor();
  // Restore the realistic fixture and check every tab at narrow phone/tablet widths.
  await page.evaluate(s=>localStorage.setItem('oneul-attendance-v1',s),JSON.stringify(C.parseBackup(backupRaw)));await page.reload();
  for(const width of [320,390,800]){
    await page.setViewportSize({width,height:844});
    for(const tab of ['attendance','students','reports','settings']){
      await click(`[data-action=tab][data-tab=${tab}]`);
      assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>(window.visualViewport?.width||window.innerWidth)+1),false,`${tab} overflows at ${width}px`);
    }
  }
  // Simulate the 00:00 date rollover: records remain, today's roster becomes unchecked.
  const rolloverContext=await browser.newContext({viewport:{width:390,height:844},locale:'ko-KR',timezoneId:'Asia/Seoul'});
  await rolloverContext.addInitScript(s=>localStorage.setItem('oneul-attendance-v1',s),JSON.stringify(C.parseBackup(backupRaw)));
  const rolloverPage=await rolloverContext.newPage();
  await rolloverPage.clock.install({time:new Date('2026-10-08T14:59:30Z')});
  await rolloverPage.goto(url);
  assert.equal(await rolloverPage.locator('.status-button.selected').count(),2);
  await rolloverPage.clock.setFixedTime(new Date('2026-10-08T15:00:01Z'));
  await rolloverPage.evaluate(()=>window.AttendanceApp.onResume());
  assert.equal(await rolloverPage.locator('#attendance-date').inputValue(),'2026-10-09');
  assert.equal(await rolloverPage.locator('.student-card').count(),2);
  assert.equal(await rolloverPage.locator('.status-button.selected').count(),0);
  const rolloverState=await rolloverPage.evaluate(()=>JSON.parse(localStorage.getItem('oneul-attendance-v1')));
  assert.equal(rolloverState.records.length,2);
  assert.equal(C.count(C.roster(C.validate(rolloverState),'2026-10-09')).unmarked,2);
  await rolloverContext.close();
  // A native persistence failure must leave the previous attendance unchanged.
  const nativePage=await context.newPage();const nativeFixture=C.parseBackup(backupRaw);await nativePage.addInitScript(s=>{window.NativeAttendance={readState:()=>JSON.stringify({ok:true,data:JSON.stringify(s)}),saveState:()=>JSON.stringify({ok:false})};},nativeFixture);await nativePage.clock.install({time:new Date('2026-10-08T07:30:00Z')});await nativePage.goto(url);await nativePage.locator('[data-action=mark][data-status=absent]').first().click();await nativePage.getByText('저장에 실패했습니다. 기기의 저장 공간을 확인해 주세요.',{exact:true}).waitFor();assert.equal(await nativePage.locator('.status-button.absent.selected').count(),0);await nativePage.close();
  // A malformed saved file is not silently replaced by an empty dataset.
  const corrupt=await context.newPage();await corrupt.addInitScript(()=>localStorage.setItem('oneul-attendance-v1','{invalid'));await corrupt.goto(url);await corrupt.getByRole('heading',{name:'데이터를 확인해 주세요'}).waitFor();assert.equal(await corrupt.evaluate(()=>localStorage.getItem('oneul-attendance-v1')),'{invalid');await corrupt.close();
  assert.deepEqual(failures,[]);
  console.log('UI passed: classless student registration, status, midnight rollover, reload, notes, bulk/undo, search, reports, CSV, backup/restore, archive history, future-date protection, XSS, 3 widths, storage failures.');
  if(process.env.QA_SCREENSHOT_DIR){
    const screenshotContext=await browser.newContext({viewport:{width:390,height:844},locale:'ko-KR',timezoneId:'Asia/Seoul',bypassCSP:true});const p=await screenshotContext.newPage();await p.clock.install({time:new Date('2026-10-08T07:30:00Z')});await p.addInitScript(s=>localStorage.setItem('oneul-attendance-v1',s),JSON.stringify(C.parseBackup(backupRaw)));await p.goto(url);
    if(process.env.QA_FONT_DIR){await p.addStyleTag({url:url+'/qa-font/400.css'});await p.addStyleTag({url:url+'/qa-font/700.css'});await p.evaluate(()=>document.fonts.ready);}
    fs.mkdirSync(process.env.QA_SCREENSHOT_DIR,{recursive:true});
    await p.screenshot({path:path.join(process.env.QA_SCREENSHOT_DIR,'attendance-phone.png'),fullPage:true});
    await p.locator('[data-tab=reports]').click();await p.screenshot({path:path.join(process.env.QA_SCREENSHOT_DIR,'reports-phone.png'),fullPage:true});
    await p.setViewportSize({width:1024,height:768});await p.locator('[data-tab=attendance]').click();await p.screenshot({path:path.join(process.env.QA_SCREENSHOT_DIR,'attendance-tablet.png'),fullPage:true});await screenshotContext.close();
  }
  await browser.close();server.close();
}
main().catch(error=>{console.error(error);server.close();process.exit(1);});
