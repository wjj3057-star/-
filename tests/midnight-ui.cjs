// Run with Playwright. Device storage and SMS are mocked; no real messages are sent.
const {chromium}=require('playwright');
const assert=require('node:assert/strict'),http=require('node:http'),fs=require('node:fs'),path=require('node:path');
const C=require('../app/src/main/assets/core.js');
const assets=path.resolve(__dirname,'../app/src/main/assets');
const server=http.createServer((req,res)=>{
  const pathname=new URL(req.url,'http://localhost').pathname;
  const file=path.resolve(assets,pathname==='/'?'index.html':'.'+pathname);
  if(!file.startsWith(assets+path.sep)||!fs.existsSync(file)||fs.statSync(file).isDirectory()){res.writeHead(404);res.end();return;}
  res.setHeader('Content-Type',file.endsWith('.css')?'text/css':file.endsWith('.js')?'application/javascript':'text/html; charset=utf-8');res.end(fs.readFileSync(file));
});
function fixture(date){
  const s=C.fresh();s.settings.sms.enabled=true;
  s.classes=[{id:'a',name:'수학반',days:[0,1,2,3,4,5,6],time:'16:00',color:'#8974C9',archived:false}];
  s.students=C.STATUSES.map((status,i)=>({id:'s'+i,name:['김하늘','박지우','이서준','최유나'][i],classId:'a',memo:'학생 메모',phone:'',contactId:'',guardians:[{id:'g'+i,name:'학부모',relation:'guardian',phone:'01011112222',contactId:'',notify:true}],active:true,joinedDate:'2026-01-01',archivedDate:null}));
  s.records=C.STATUSES.map((status,i)=>({studentId:'s'+i,classId:'a',date,status,note:'지난 날짜 메모 '+i,studentName:s.students[i].name,className:'수학반',time:i<2?'16:00':'',updatedAt:date+'T07:00:00.000Z'}));
  return s;
}
async function main(){
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));const url=`http://127.0.0.1:${server.address().port}`;
  const browser=await chromium.launch({headless:true,...(process.env.CHROMIUM_PATH?{executablePath:process.env.CHROMIUM_PATH}:{}),args:JSON.parse(process.env.CHROMIUM_ARGS_JSON||'[]')});
  const errors=[];
  async function open(time,seed=fixture(time.slice(0,10))){
    const context=await browser.newContext({viewport:{width:390,height:844},timezoneId:'Asia/Seoul',locale:'ko-KR'});
    await context.addInitScript(seed=>{
      if(!localStorage.getItem('test-state'))localStorage.setItem('test-state',JSON.stringify(seed));
      window.mockWrites=0;window.mockSms=[];
      window.NativeAttendance={
        readState:()=>JSON.stringify({ok:true,data:localStorage.getItem('test-state')}),
        saveState:raw=>{window.mockWrites++;localStorage.setItem('test-state',raw);return JSON.stringify({ok:true});},
        sendAttendanceSms:raw=>window.mockSms.push(JSON.parse(raw)),
        deviceInfo:()=>JSON.stringify({smsGranted:true,smsCapable:true,simReady:true}),smsLogs:()=>JSON.stringify({ok:true,jobs:[]})
      };
    },seed);
    const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));
    await page.clock.install({time:new Date(new Date(time).getTime()-1000)});await page.clock.pauseAt(new Date(time));
    await page.goto(url);await page.locator('#attendance-date').waitFor();return {context,page};
  }
  const raw=p=>p.evaluate(()=>localStorage.getItem('test-state'));
  const date=p=>p.locator('#attendance-date').inputValue();
  const selected=p=>p.locator('.status-button.selected').count();
  const effects=p=>p.evaluate(()=>({writes:window.mockWrites,sms:window.mockSms.length}));
  // Start away from the minute polling boundary: the dedicated midnight timer is required.
  {
    const {context,page:p}=await open('2026-10-09T23:59:58.500+09:00'),before=await raw(p);
    assert.equal(await selected(p),4);await p.clock.runFor(1400);assert.equal(await date(p),'2026-10-09');assert.equal(await selected(p),4);
    await p.clock.runFor(150);assert.equal(await date(p),'2026-10-10');assert.equal(await selected(p),0);assert.equal(await p.locator('.record-note,.student-meta').count(),0);
    assert.equal(await raw(p),before);assert.deepEqual(await effects(p),{writes:0,sms:0});
    await p.locator('[data-action=mark][data-id=s0][data-status=present]').click();assert.equal(await selected(p),1);
    assert.equal(await p.evaluate(()=>window.mockSms[0][0].date),'2026-10-10');const after=await raw(p);assert.equal(JSON.parse(after).records.length,5);
    // Reopening or resuming on the same day preserves the new day's attendance.
    await p.reload();await p.locator('#attendance-date').waitFor();await p.evaluate(()=>window.AttendanceApp.onResume());assert.equal(await selected(p),1);assert.equal(await raw(p),after);
    // The timer must re-arm every day; the following midnight needs no manual resume.
    await p.clock.fastForward(24*60*60*1000);assert.equal(await date(p),'2026-10-11');assert.equal(await selected(p),0);assert.equal(await raw(p),after);assert.deepEqual(await effects(p),{writes:0,sms:0});
    await p.locator('#attendance-date').fill('2026-10-09');await p.locator('#attendance-date').dispatchEvent('change');assert.equal(await selected(p),4);assert.equal(await p.locator('.record-note').count(),4);
    await context.close();
  }
  {
    const {context,page:p}=await open('2026-10-09T11:59:58.500+09:00'),before=await raw(p);await p.clock.runFor(2000);
    assert.equal(await date(p),'2026-10-09');assert.equal(await selected(p),4);assert.equal(await raw(p),before);assert.deepEqual(await effects(p),{writes:0,sms:0});await context.close();
  }
  // A dated note form cannot carry yesterday's status or note into the new year.
  {
    const {context,page:p}=await open('2026-12-31T23:59:58.500+09:00'),before=await raw(p);
    await p.locator('[data-action=record-note][data-id=s0]').click();await p.locator('[name=note]').fill('저장하지 않은 메모');
    await p.clock.runFor(1550);assert.equal(await date(p),'2027-01-01');assert.equal(await p.locator('.modal').count(),0);assert.equal(await raw(p),before);assert.deepEqual(await effects(p),{writes:0,sms:0});
    await p.locator('[data-tab=reports]').click();assert.equal(await p.locator('#report-month').inputValue(),'2027-01');await context.close();
  }
  // Simulate suspended timers: a pending confirmation itself must check the date.
  for(const kind of ['bulk','clear']){
    const seed=fixture('2026-10-09');if(kind==='bulk')seed.records.pop();
    const {context,page:p}=await open('2026-10-09T23:59:58.500+09:00',seed),before=await raw(p);
    if(kind==='bulk')await p.locator('[data-action=bulk-present]').click();
    else{await p.locator('[data-action=record-note][data-id=s0]').click();await p.locator('[data-action=clear-record]').click();}
    await p.clock.setFixedTime(new Date('2026-10-10T00:00:01+09:00'));await p.locator('#confirm-action').click();
    assert.equal(await date(p),'2026-10-10');assert.equal(await p.locator('.modal').count(),0);assert.equal(await raw(p),before);assert.deepEqual(await effects(p),{writes:0,sms:0});await context.close();
  }
  for(const action of ['mark','undo']){
    const {context,page:p}=await open('2026-10-09T23:59:58.500+09:00');
    if(action==='undo')await p.locator('[data-action=mark][data-id=s0][data-status=late]').click();
    const before=await raw(p),count=await effects(p);await p.clock.setFixedTime(new Date('2026-10-10T00:00:01+09:00'));
    await p.locator(action==='undo'?'[data-action=undo]':'[data-action=mark][data-id=s0][data-status=late]').click();
    assert.equal(await date(p),'2026-10-10');assert.equal(await raw(p),before);assert.deepEqual(await effects(p),count);assert.equal(await p.locator('[data-action=undo]').count(),0);await context.close();
  }
  // Student/guardian form edits survive the same boundary; only date-bound actions expire.
  {
    const {context,page:p}=await open('2026-10-09T23:59:58.500+09:00'),before=await raw(p);
    await p.locator('[data-action=student-add]').click();await p.locator('[name=name]').fill('신규 학생');await p.locator('[name=g-phone-0]').fill('01099998888');
    await p.clock.runFor(1550);assert.equal(await p.locator('[name=name]').inputValue(),'신규 학생');assert.equal(await p.locator('[name=g-phone-0]').inputValue(),'01099998888');assert.equal(await p.locator('[name=joinedDate]').getAttribute('max'),'2026-10-10');assert.equal(await date(p),'2026-10-10');assert.equal(await raw(p),before);await context.close();
  }
  {
    const {context,page:p}=await open('2026-10-09T16:00:00+09:00'),before=await raw(p);
    await p.clock.setFixedTime(new Date('2026-10-12T08:00:00+09:00'));await p.evaluate(()=>window.AttendanceApp.onResume());assert.equal(await date(p),'2026-10-12');assert.equal(await selected(p),0);assert.equal(await raw(p),before);
    await p.clock.setFixedTime(new Date('2026-10-15T09:00:00+09:00'));await p.reload();await p.locator('#attendance-date').waitFor();assert.equal(await date(p),'2026-10-15');assert.equal(await raw(p),before);assert.deepEqual(await effects(p),{writes:0,sms:0});await context.close();
  }
  assert.deepEqual(errors,[]);
  console.log('Midnight UI passed: exact local 00:00, consecutive days, no noon reset, year/month boundary, history preserved, same-day restart, paused confirmations/taps/undo, unfinished student form, multi-day resume/cold start, no reset-time writes or SMS.');
  await browser.close();server.close();
}
main().catch(error=>{console.error(error);server.close();process.exit(1);});
