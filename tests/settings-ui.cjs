// Settings and permission regressions. All contacts, device storage and SMS are mocked.
const {chromium}=require('playwright');
const assert=require('node:assert/strict'),http=require('node:http'),fs=require('node:fs'),path=require('node:path');
const C=require('../app/src/main/assets/core.js'),assets=path.resolve(__dirname,'../app/src/main/assets');
const server=http.createServer((req,res)=>{
  const pathname=new URL(req.url,'http://localhost').pathname,file=path.resolve(assets,pathname==='/'?'index.html':'.'+pathname);
  if(!file.startsWith(assets+path.sep)||!fs.existsSync(file)||fs.statSync(file).isDirectory()){res.writeHead(404);res.end();return;}
  res.setHeader('Content-Type',file.endsWith('.css')?'text/css':file.endsWith('.js')?'application/javascript':'text/html; charset=utf-8');res.end(fs.readFileSync(file));
});
async function main(){
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));const url=`http://127.0.0.1:${server.address().port}`;
  const browser=await chromium.launch({headless:true,...(process.env.CHROMIUM_PATH?{executablePath:process.env.CHROMIUM_PATH}:{}),args:JSON.parse(process.env.CHROMIUM_ARGS_JSON||'[]')});
  const context=await browser.newContext({viewport:{width:390,height:844},locale:'ko-KR',timezoneId:'Asia/Seoul'}),page=await context.newPage(),errors=[];
  page.on('pageerror',e=>errors.push(e.message));const seed=C.fresh();
  seed.students=[{id:'s1',name:'김하늘',classId:C.DEFAULT_CLASS_ID,memo:'',phone:'',contactId:'',guardians:[{id:'g1',name:'김하늘 어머니',phone:'01011112222',relation:'mother',notify:true,contactId:''}],active:true,joinedDate:'2026-01-01',archivedDate:null}];
  await page.addInitScript(seed=>{
    if(!localStorage.getItem('settings-test'))localStorage.setItem('settings-test',JSON.stringify(seed));
    window.mockDevice={contactsGranted:false,smsGranted:true,smsCapable:true,simReady:true};window.mockCalls=[];window.mockSaveFails=false;window.mockContactReads=0;
    window.NativeAttendance={readState:()=>JSON.stringify({ok:true,data:localStorage.getItem('settings-test')}),
      saveState:raw=>{if(window.mockSaveFails)return JSON.stringify({ok:false,error:'저장 공간이 부족해요. 다시 시도해 주세요.'});localStorage.setItem('settings-test',raw);return JSON.stringify({ok:true});},
      deviceInfo:()=>JSON.stringify(window.mockDevice),sendAttendanceSms:raw=>window.mockCalls.push(JSON.parse(raw)),
      requestSmsPermission:()=>setTimeout(()=>window.ContactSms.onPermission(window.mockDevice),0),openAppSettings:()=>{},
      smsLogs:()=>JSON.stringify({ok:true,jobs:[]}),
      loadContacts:requestId=>{window.mockContactReads++;setTimeout(()=>window.ContactSms.onContacts(window.mockDevice.contactsGranted?{ok:true,requestId,contacts:[{id:'1',name:'김하늘 학생',phone:'01022223333'}]}:{ok:false,requestId,error:'연락처 권한이 거부됐어요.'}),0);}
    };
  },seed);
  await page.clock.install({time:new Date('2026-10-09T07:00:00Z')});await page.goto(url);
  const action=s=>page.locator(`[data-contact-action=${s}]`),tab=s=>page.locator(`[data-tab=${s}]`).click(),close=()=>page.locator('.modal-header [data-action=close]').click();
  const read=()=>page.evaluate(()=>JSON.parse(localStorage.getItem('settings-test'))),calls=()=>page.evaluate(()=>window.mockCalls.length);
  const submit=()=>page.locator('#modal-root button[type=submit]').click();
  await tab('settings');await page.locator('[data-action=academy-edit]').click();await page.locator('[name=academy]').fill('하늘 학원');await page.locator('[name=teacher]').fill('이선생');await submit();assert.equal((await read()).settings.academy,'하늘 학원');
  await page.reload();await tab('settings');await page.locator('[data-action=academy-edit]').click();assert.equal(await page.locator('[name=teacher]').inputValue(),'이선생');await close();
  // This used to block all preference saving when permission or a SIM was unavailable.
  await page.evaluate(()=>Object.assign(window.mockDevice,{smsGranted:false,simReady:false}));await action('sms-settings').click();
  const template='[{학원명}] {학생이름} · {상태} · {시간}';await page.locator('[name=enabled]').check();await page.locator('#sms-template').fill(template);await submit();
  assert.equal((await read()).settings.sms.template,template,'SMS preferences must save even before permission/SIM setup');assert.equal((await read()).settings.sms.enabled,true);assert.equal(await page.locator('.modal').count(),0);assert.equal(await calls(),0);
  await action('sms-settings').click();assert.equal(await action('sms-permission').isVisible(),true);await action('sms-permission').click();assert.equal(await page.locator('#sms-template').inputValue(),template);
  // Grant/deny callbacks and returning from system settings update controls in place.
  const draft=template+' 안내';await page.locator('#sms-template').fill(draft);
  await page.evaluate(()=>{window.mockDevice.smsGranted=true;window.ContactSms.onPermission(window.mockDevice);});assert.equal(await action('sms-permission').isVisible(),false);assert.equal(await page.locator('#sms-permission-actions [data-contact-action=app-settings]').isVisible(),false);assert.equal(await page.locator('#sms-template').inputValue(),draft);assert.ok((await page.locator('#sms-permission-state').textContent()).includes('SIM'));
  await page.evaluate(()=>{window.mockDevice.simReady=true;window.AttendanceApp.onResume();});assert.ok((await page.locator('#sms-permission-state').textContent()).includes('준비 완료'));assert.equal(await page.locator('#sms-template').inputValue(),draft);
  await page.evaluate(()=>{window.mockDevice.smsGranted=false;window.AttendanceApp.onResume();});assert.equal(await action('sms-permission').isVisible(),true);await submit();assert.equal((await read()).settings.sms.template,draft);
  // Readiness is enforced before dispatch, not before persisting preferences.
  await tab('attendance');await page.locator('[data-action=mark][data-status=present]').click();assert.equal((await read()).records[0].status,'present');assert.equal(await calls(),0);
  await page.evaluate(()=>Object.assign(window.mockDevice,{smsGranted:true,simReady:false}));await page.locator('[data-action=mark][data-status=late]').click();assert.equal(await calls(),0);
  await page.evaluate(()=>window.mockDevice.simReady=true);await page.locator('[data-action=mark][data-status=present]').click();assert.equal(await calls(),1);
  await tab('settings');await action('sms-settings').click();assert.equal(await action('sms-permission').isVisible(),false);await page.locator('[name=enabled]').uncheck();for(const box of await page.locator('[name=statuses]').all())await box.uncheck();await submit();assert.equal((await read()).settings.sms.enabled,false);assert.deepEqual((await read()).settings.sms.statuses,[]);
  await action('sms-settings').click();await page.locator('[name=enabled]').check();await submit();assert.ok((await page.locator('#form-error').textContent()).includes('하나 이상'));await page.locator('[name=statuses][value=present]').check();await page.locator('#sms-template').fill('{잘못된항목}');await submit();assert.ok((await page.locator('#form-error').textContent()).includes('지원하지 않는'));await page.locator('#sms-template').fill(draft);await submit();
  await action('keywords').click();await page.locator('[name=mother]').fill('어머니, 엄마, Mom');await submit();assert.deepEqual((await read()).settings.contactKeywords.mother,['어머니','엄마','Mom']);
  // Real persistence errors leave edits visible, show an inline error and allow retry.
  await page.locator('[data-action=academy-edit]').click();await page.locator('[name=academy]').fill('새 학원');await page.evaluate(()=>window.mockSaveFails=true);await submit();assert.equal((await read()).settings.academy,'하늘 학원');assert.equal(await page.locator('#form-error').isVisible(),true);assert.ok((await page.locator('#form-error').textContent()).includes('저장 공간'));assert.equal(await page.locator('[name=academy]').inputValue(),'새 학원');await page.evaluate(()=>window.mockSaveFails=false);await submit();assert.equal((await read()).settings.academy,'새 학원');
  await page.reload();await tab('settings');await action('sms-settings').click();assert.equal(await page.locator('#sms-template').inputValue(),draft);assert.equal(await page.locator('[name=enabled]').isChecked(),true);assert.equal(await action('sms-permission').isVisible(),false);await close();
  // Contact permission recovery resumes the pending picker without losing entered data.
  await tab('students');await page.locator('[data-action=student-add]').click();await page.locator('[name=name]').fill('입력 중인 이름');await page.locator('[data-target=student]').click();await page.getByText('연락처 권한이 거부됐어요.',{exact:true}).waitFor();
  await page.evaluate(()=>{window.mockDevice.contactsGranted=true;window.AttendanceApp.onResume();});await page.locator('[data-contact-action=contact-select]').waitFor();assert.equal(await page.locator('[data-permission-needed=contacts]').isVisible(),false);await action('picker-back').click();assert.equal(await page.locator('[name=name]').inputValue(),'입력 중인 이름');await close();
  await page.setViewportSize({width:320,height:844});await tab('settings');await action('sms-settings').click();assert.equal(await page.evaluate(()=>document.querySelector('.modal').scrollWidth>document.querySelector('.modal').clientWidth+1),false);
  assert.deepEqual(errors,[]);console.log('Settings UI passed: preference persistence/restart, pending permissions/SIM, grant/revoke button visibility, draft retention, pre-send readiness, disable with no statuses, validation, save-error retry, contact recovery, 320px layout.');
  await browser.close();server.close();
}
main().catch(error=>{console.error(error);server.close();process.exit(1);});
