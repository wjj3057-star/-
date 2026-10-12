// Audit regression cases. Native phone/storage calls are simulated; no messages are sent.
const {chromium}=require('playwright');
const assert=require('node:assert/strict'),http=require('node:http'),fs=require('node:fs'),path=require('node:path');
const C=require('../app/src/main/assets/core.js'),assets=path.resolve(__dirname,'../app/src/main/assets');
const server=http.createServer((req,res)=>{const n=new URL(req.url,'http://localhost').pathname,f=path.resolve(assets,n==='/'?'index.html':'.'+n);if(!f.startsWith(assets+path.sep)||!fs.existsSync(f)){res.writeHead(404);res.end();return;}res.setHeader('Content-Type',f.endsWith('.css')?'text/css':f.endsWith('.js')?'application/javascript':'text/html; charset=utf-8');res.end(fs.readFileSync(f));});
async function main(){
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const url=`http://127.0.0.1:${server.address().port}`;
 const browser=await chromium.launch({headless:true,...(process.env.CHROMIUM_PATH?{executablePath:process.env.CHROMIUM_PATH}:{}),args:JSON.parse(process.env.CHROMIUM_ARGS_JSON||'[]')});
 const failures=[];
 const fixture=C.fresh();fixture.students=[{id:'s1',name:'김하늘',classId:C.DEFAULT_CLASS_ID,memo:'',phone:'',contactId:'',guardians:[],active:true,joinedDate:'2026-01-01',archivedDate:null}];
 async function check(name,run){const p=await browser.newPage({viewport:{width:390,height:844},locale:'ko-KR',timezoneId:'Asia/Seoul'});const errors=[];p.on('pageerror',e=>errors.push(e.message));await p.addInitScript(s=>{
  window.testState=s;window.testContacts=Array.from({length:205},(_,i)=>({id:String(i),name:'학생 '+String(i+1).padStart(3,'0'),phone:'010'+String(10000000+i)}));window.readError=false;window.testLogs=[];
  window.NativeAttendance={readState:()=>JSON.stringify({ok:true,data:JSON.stringify(window.testState)}),saveState:raw=>{window.testState=JSON.parse(raw);return JSON.stringify({ok:true});},deviceInfo:()=>JSON.stringify({contactsGranted:true,smsGranted:true,simReady:true,smsCapable:true}),smsLogs:()=>JSON.stringify({ok:true,jobs:window.testLogs}),loadContacts:requestId=>setTimeout(()=>window.ContactSms.onContacts(window.readError?{requestId,ok:false,error:'연락처 제공자 오류'}:{requestId,ok:true,contacts:window.testContacts}),0)};
 },fixture);await p.clock.install({time:new Date('2026-10-12T07:00:00Z')});await p.goto(url);
 try{await run(p);assert.deepEqual(errors,[]);console.log('PASS '+name);}catch(e){failures.push(name+': '+e.message);}finally{await p.close();}}
 await check('cleared date and month recover visible selection',async p=>{
  await p.locator('#attendance-date').fill('');await p.locator('#attendance-date').dispatchEvent('change');assert.equal(await p.locator('#attendance-date').inputValue(),'2026-10-12');
  await p.locator('[data-tab=reports]').click();await p.locator('#report-month').fill('');await p.locator('#report-month').dispatchEvent('change');assert.equal(await p.locator('#report-month').inputValue(),'2026-10');
 });
 await check('contact picker can reach beyond the first 100 and retains the draft',async p=>{
  await p.locator('[data-action=student-add]').click();await p.locator('[name=name]').fill('입력 중');await p.locator('[data-target=student]').click();await p.locator('[data-contact-action=contact-select]').first().waitFor();
  assert.equal(await p.locator('[data-contact-action=contacts-more]').count(),1);
  await p.locator('[data-contact-action=contacts-more]').click();await p.locator('[data-contact-action=contacts-more]').click();assert.equal(await p.locator('[data-contact-action=contact-select]').count(),205);
  await p.locator('[data-contact-action=contact-select]').last().click();assert.equal(await p.locator('[name=phone]').inputValue(),'01010000204');assert.equal(await p.locator('[name=name]').inputValue(),'입력 중');
 });
 await check('provider failure can be retried after permission is already granted',async p=>{
  await p.evaluate(()=>window.readError=true);await p.locator('[data-action=student-add]').click();await p.locator('[name=name]').fill('김하늘');await p.locator('[data-target=student]').click();await p.getByText('연락처 제공자 오류',{exact:true}).waitFor();assert.equal(await p.locator('[data-contact-action=contacts-retry]').count(),1);
  await p.evaluate(()=>window.readError=false);await p.locator('[data-contact-action=contacts-retry]').click();await p.locator('[data-contact-action=contact-select]').first().waitFor();await p.locator('[data-contact-action=picker-back]').click();assert.equal(await p.locator('[name=name]').inputValue(),'김하늘');
 });
 await check('obsolete failed SMS entries no longer offer duplicate retries',async p=>{
  await p.evaluate(()=>window.testLogs=[{id:'old',studentName:'김하늘',attendanceStatus:'present',guardianName:'어머니',phone:'01011112222',createdAt:Date.now(),date:'2026-10-12',parts:1,message:'테스트',state:'failed',error:'통신 오류',canRetry:false,retryUnavailableReason:'이미 발송 완료된 문자가 있어 재전송하지 않아요.'}]);await p.locator('[data-tab=settings]').click();await p.locator('[data-contact-action=sms-log]').click();assert.equal(await p.locator('[data-contact-action=retry]').count(),0);await p.getByText('이미 발송 완료된 문자가 있어 재전송하지 않아요.',{exact:true}).waitFor();
 });
 await browser.close();server.close();assert.deepEqual(failures,[]);
 console.log('Audit UI passed: date clearing, full contact list, provider retry, preserved drafts and retry eligibility.');
}
main().catch(e=>{console.error(e);server.close();process.exit(1);});
