// Playwright integration test. All phone contacts, permissions and SMS calls are mocked.
// Uses the same optional CHROMIUM_PATH / CHROMIUM_ARGS_JSON / QA_* variables as ui.cjs.
const {chromium}=require('playwright'),assert=require('node:assert/strict');
const http=require('node:http'),fs=require('node:fs'),path=require('node:path');
const C=require('../app/src/main/assets/core.js');
const assets=path.resolve(__dirname,'../app/src/main/assets');
const server=http.createServer((req,res)=>{
  const pathname=new URL(req.url,'http://localhost').pathname;
  const font=pathname.startsWith('/qa-font/')&&process.env.QA_FONT_DIR;
  const base=font?path.resolve(process.env.QA_FONT_DIR):assets;
  const file=path.resolve(base,font?pathname.slice(9):pathname==='/'?'index.html':'.'+pathname);
  if(!file.startsWith(base+path.sep)||!fs.existsSync(file)||fs.statSync(file).isDirectory()){res.writeHead(404);res.end();return;}
  res.setHeader('Content-Type',file.endsWith('.css')?'text/css':file.endsWith('.js')?'application/javascript':file.endsWith('.woff2')?'font/woff2':'text/html; charset=utf-8');res.end(fs.readFileSync(file));
});
const seed=C.fresh();seed.classes=['a','b'].map(id=>({id,name:'수학 '+id.toUpperCase()+'반',days:[0,1,2,3,4,5,6],time:'16:00',color:'#8974C9',archived:false}));
seed.students=['a','b'].map((classId,i)=>({id:'s'+i,name:'김하늘',classId,memo:'기존 학생',active:true,joinedDate:'2026-10-01',archivedDate:null,phone:'',contactId:'',guardians:[]}));
const contacts=[{id:'1',name:'(김하늘)어머니',phone:'01011112222'},{id:'2',name:'김하늘 아버지',phone:'01033334444'},{id:'3',name:'김하늘',phone:'01055556666'},{id:'4',name:'김하늘 엄마',phone:'+82 10-1111-2222'},{id:'5',name:'박지우 아빠',phone:'01077778888'},{id:'6',name:'박지우 학생',phone:'01099990000'},{id:'7',name:'음식점',phone:'0212345678'}];
function nativeMock({seed,contacts}){
  window.mockContacts=contacts;window.mockCalls=[];window.mockLogs=[];window.mockRetries=[];
  window.mockDevice={contactsGranted:true,smsGranted:false,smsCapable:true,simReady:true};window.mockSaveFails=false;window.mockContactDenied=false;
  if(!localStorage.getItem('test-native-state'))localStorage.setItem('test-native-state',JSON.stringify(seed));
  window.NativeAttendance={
    readState:()=>JSON.stringify({ok:true,data:localStorage.getItem('test-native-state')}),
    saveState:raw=>{if(window.mockSaveFails)return JSON.stringify({ok:false});localStorage.setItem('test-native-state',raw);return JSON.stringify({ok:true});},
    deviceInfo:()=>JSON.stringify(window.mockDevice),
    loadContacts:requestId=>setTimeout(()=>window.ContactSms.onContacts(window.mockContactDenied?{ok:false,requestId,error:'연락처 권한이 거부됐어요.'}:{ok:true,requestId,contacts:window.mockContacts}),0),
    requestSmsPermission:()=>setTimeout(()=>window.ContactSms.onPermission(window.mockDevice),0),
    openAppSettings:()=>{},
    sendAttendanceSms:raw=>{window.mockCalls.push(JSON.parse(raw));setTimeout(()=>window.ContactSms.onSmsQueue({ok:true,queued:2,skipped:0,failed:0}),0);},
    smsLogs:()=>JSON.stringify({ok:true,jobs:window.mockLogs}),retrySms:id=>window.mockRetries.push(id)
  };
}
async function main(){
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));const url=`http://127.0.0.1:${server.address().port}`;
  const browser=await chromium.launch({headless:true,...(process.env.CHROMIUM_PATH?{executablePath:process.env.CHROMIUM_PATH}:{}),args:JSON.parse(process.env.CHROMIUM_ARGS_JSON||'[]')});
  const context=await browser.newContext({viewport:{width:390,height:844},locale:'ko-KR',timezoneId:'Asia/Seoul'});
  await context.addInitScript(nativeMock,{seed,contacts});const page=await context.newPage(),errors=[];page.on('pageerror',err=>errors.push(err.message));
  await page.clock.install({time:new Date('2026-10-09T07:30:00Z')});await page.goto(url);
  const action=s=>page.locator(`[data-contact-action=${s}]`),tab=s=>page.locator(`[data-tab=${s}]`).click();
  const read=()=>page.evaluate(()=>JSON.parse(localStorage.getItem('test-native-state')));
  const callCount=()=>page.evaluate(()=>window.mockCalls.length);
  const close=()=>page.locator('.modal-header [data-action=close]').click();
  await tab('students');await action('auto-register').click();await action('scan').click();await page.locator('.candidate-card').first().waitFor();
  const kim=page.locator('.candidate-card').filter({has:page.locator('input[value="김하늘"]')});
  assert.equal(await kim.locator('[name^=use-]').isChecked(),false);assert.equal(await kim.locator('[name^=guardian-]').count(),2);
  await kim.locator('[name^=use-]').check();await page.getByRole('button',{name:'선택한 학생 등록',exact:true}).click();await page.getByText('김하늘 학생의 연결 대상을 직접 선택해 주세요.',{exact:true}).waitFor();assert.equal((await read()).students.length,2);
  await kim.locator('[name^=target-]').selectOption('s0');await page.getByRole('button',{name:'선택한 학생 등록',exact:true}).click();
  let data=await read();assert.equal(data.students.length,3);assert.equal(data.students[0].guardians.length,2);assert.equal(data.students[1].guardians.length,0);assert.equal(data.students[0].phone,'01055556666');assert.equal(await callCount(),0);
  // Rescanning the same contacts preserves IDs and never duplicates guardian numbers.
  await action('auto-register').click();await action('scan').click();await page.locator('.candidate-card').first().waitFor();await kim.locator('[name^=use-]').check();await kim.locator('[name^=target-]').selectOption('s0');await page.getByRole('button',{name:'선택한 학생 등록',exact:true}).click();assert.equal((await read()).students[0].guardians.length,2);
  // Manual selection keeps unfinished form fields, including on permission denial/back.
  await page.evaluate(()=>window.mockContacts.push({id:'8',name:'이서준 학생',phone:'01022223333'},{id:'9',name:'이서준 엄마',phone:'01044445555'}));
  await page.locator('[data-action=student-add]').click();await page.locator('[name=memo]').fill('새로 등록한 학생');await page.evaluate(()=>window.mockContactDenied=true);
  await page.locator('[data-target=student]').click();await page.getByText('연락처 권한이 거부됐어요.',{exact:true}).waitFor();await action('picker-back').click();assert.equal(await page.locator('[name=memo]').inputValue(),'새로 등록한 학생');
  await page.evaluate(()=>window.mockContactDenied=false);await page.locator('[data-target=student]').click();await page.locator('#contact-search').fill('서준');await action('contact-select').filter({hasText:'이서준 학생'}).click();assert.equal(await page.locator('[name=name]').inputValue(),'이서준');
  await page.locator('[data-target=guardian-0]').click();await page.locator('#contact-search').fill('서준');await action('contact-select').filter({hasText:'이서준 엄마'}).click();await page.locator('[name=g-notify-0]').uncheck();await page.locator('#modal-root').getByRole('button',{name:'학생 등록',exact:true}).click();data=await read();assert.equal(data.students.at(-1).guardians[0].notify,false);assert.equal(await callCount(),0);
  // Save preferences before permission/SIM setup; validate the template separately.
  await tab('settings');await action('sms-settings').click();assert.equal(await page.locator('[name=enabled]').isChecked(),false);assert.equal(await page.locator('#sms-template').inputValue(),C.DEFAULT_TEMPLATE);
  await page.locator('[name=enabled]').check();await page.getByRole('button',{name:'문자 설정 저장',exact:true}).click();assert.equal((await read()).settings.sms.enabled,true);assert.equal(await callCount(),0);
  await page.evaluate(()=>Object.assign(window.mockDevice,{smsGranted:true,simReady:false}));await action('sms-settings').click();assert.equal(await action('sms-permission').isVisible(),false);await page.getByRole('button',{name:'문자 설정 저장',exact:true}).click();assert.equal((await read()).settings.sms.enabled,true);
  await page.evaluate(()=>window.mockDevice.simReady=true);await action('sms-settings').click();await page.locator('#sms-template').fill('{지원안함}');await page.getByRole('button',{name:'문자 설정 저장',exact:true}).click();await page.getByText('지원하지 않는 항목: 지원안함',{exact:true}).waitFor();
  const template='[{학원명}] {학생이름} 학생 {상태} · {날짜} {시간}';await page.locator('#sms-template').fill(template);assert.ok((await page.locator('#sms-preview-text').textContent()).includes('김하늘 학생 출석'));await page.getByRole('button',{name:'문자 설정 저장',exact:true}).click();assert.equal((await read()).settings.sms.template,template);
  // Only a successful save with a new allowed status triggers the bridge.
  await tab('attendance');const mark=st=>page.locator(`[data-action=mark][data-id=s0][data-status=${st}]`).click();await mark('present');assert.equal(await callCount(),1);assert.equal((await read()).records[0].status,'present');
  await page.locator('[data-action=record-note][data-id=s0]').click();await page.locator('[name=note]').fill('메모만 수정');await page.getByRole('button',{name:'메모 저장',exact:true}).click();assert.equal(await callCount(),1);
  await page.evaluate(()=>window.mockSaveFails=true);await mark('late');assert.equal(await callCount(),1);assert.equal((await read()).records[0].status,'present');await page.evaluate(()=>window.mockSaveFails=false);
  await mark('late');assert.equal(await callCount(),2);await mark('late');assert.equal(await callCount(),2);await mark('absent');assert.equal(await callCount(),2);
  await page.locator('#attendance-date').fill('2026-10-08');await page.locator('#attendance-date').dispatchEvent('change');await mark('present');assert.equal(await callCount(),2);
  // Restoring a backup disables future automatic sends and does not send anything itself.
  const backup=C.backup(await read());await page.evaluate(raw=>window.AttendanceApp.onImport(raw),backup);await page.locator('#restore-consent').check();await page.locator('#restore-confirm').click();assert.equal((await read()).settings.sms.enabled,false);assert.equal(await callCount(),2);
  // Success, failure and unknown outcomes have distinct labels; retry needs confirmation.
  await page.evaluate(()=>window.mockLogs=['sent','failed','unknown'].map((state,i)=>({id:String(i),studentName:'김하늘',guardianName:'김하늘 어머니',phone:'01011112222',attendanceStatus:'present',date:'2026-10-09',createdAt:Date.now(),parts:2,message:'출석 안내',state,error:state==='sent'?'':'통신 상태를 확인해 주세요.'})));
  await tab('settings');await action('sms-log').click();assert.equal(await page.locator('.sms-state.sent').textContent(),'발송 완료');assert.equal(await page.locator('.sms-state.unknown').textContent(),'결과 미확인');await action('retry').first().click();assert.equal(await page.evaluate(()=>window.mockRetries.length),0);await page.locator('#confirm-action').click();assert.deepEqual(await page.evaluate(()=>window.mockRetries),['1']);await close();
  // New dialogs remain usable at the narrowest supported phone width.
  await page.setViewportSize({width:320,height:844});
  for(const name of ['sms-settings','keywords','auto-register']){await action(name).click();if(name==='auto-register'){await action('scan').click();await page.locator('.candidate-card').first().waitFor();}assert.equal(await page.evaluate(()=>document.querySelector('.modal').scrollWidth>document.querySelector('.modal').clientWidth+1),false,name+' overflow');await close();}
  await tab('students');await page.locator('[data-action=student-add]').click();assert.equal(await page.evaluate(()=>document.querySelector('.modal').scrollWidth>document.querySelector('.modal').clientWidth+1),false,'student form overflow');await close();
  assert.deepEqual(errors,[]);console.log('Contacts/SMS UI passed: name ambiguity, import/rescan, manual picker, denied permission, SIM checks, template editing, save-before-send, status-only dispatch, restore disable, result/retry UI, 320px layout. No real contacts or SMS used.');
  if(process.env.QA_SCREENSHOT_DIR){
    const sc=await browser.newContext({viewport:{width:390,height:844},locale:'ko-KR',timezoneId:'Asia/Seoul',bypassCSP:true});await sc.addInitScript(nativeMock,{seed:await read(),contacts});const p=await sc.newPage();await p.clock.install({time:new Date('2026-10-09T07:30:00Z')});await p.goto(url);
    if(process.env.QA_FONT_DIR){await p.addStyleTag({url:url+'/qa-font/400.css'});await p.addStyleTag({url:url+'/qa-font/700.css'});await p.evaluate(()=>document.fonts.ready);}
    fs.mkdirSync(process.env.QA_SCREENSHOT_DIR,{recursive:true});await p.locator('[data-tab=settings]').click();await p.evaluate(()=>window.mockDevice.smsGranted=true);await p.locator('[data-contact-action=sms-settings]').click();await p.screenshot({path:path.join(process.env.QA_SCREENSHOT_DIR,'sms-settings-phone.png')});await p.locator('.modal-header [data-action=close]').click();await p.locator('[data-contact-action=auto-register]').click();await p.locator('[data-contact-action=scan]').click();await p.locator('.candidate-card').first().waitFor();await p.screenshot({path:path.join(process.env.QA_SCREENSHOT_DIR,'contact-import-phone.png')});await sc.close();
  }
  await browser.close();server.close();
}
main().catch(error=>{console.error(error);server.close();process.exit(1);});
