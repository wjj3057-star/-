// Synthetic local data and a mock Android bridge; no real contacts or SMS.
const {chromium}=require('playwright');
const assert=require('node:assert/strict'),http=require('node:http'),fs=require('node:fs'),path=require('node:path');
const C=require('../app/src/main/assets/core.js'),assets=path.resolve(__dirname,'../app/src/main/assets');
const server=http.createServer((req,res)=>{
  const name=new URL(req.url,'http://localhost').pathname,file=path.resolve(assets,name==='/'?'index.html':'.'+name);
  if(!file.startsWith(assets+path.sep)||!fs.existsSync(file)){res.writeHead(404);res.end();return;}
  res.setHeader('Content-Type',file.endsWith('.css')?'text/css':file.endsWith('.js')?'application/javascript':'text/html; charset=utf-8');res.end(fs.readFileSync(file));
});
function fixture(){const s=C.fresh();s.students=[{id:'s1',name:'김하늘',classId:C.DEFAULT_CLASS_ID,memo:'',phone:'',contactId:'',active:true,joinedDate:'2026-01-01',archivedDate:null,guardians:[{id:'g1',name:'김하늘 어머니',relation:'mother',phone:'01011112222',notify:false,contactId:''}]}];return s;}
async function edit(p){await p.locator('[data-action=student-detail][data-id=s1]').click();await p.locator('[data-action=student-edit]').click();}
const read=p=>p.evaluate(()=>window.mockState);
async function main(){
  await new Promise(r=>server.listen(0,'127.0.0.1',r));
  const browser=await chromium.launch({headless:true,...(process.env.CHROMIUM_PATH?{executablePath:process.env.CHROMIUM_PATH}:{}),args:JSON.parse(process.env.CHROMIUM_ARGS_JSON||'[]')}),failures=[];
  async function check(name,run){const p=await browser.newPage({viewport:{width:390,height:844},locale:'ko-KR',timezoneId:'Asia/Seoul'}),errors=[];
    p.on('pageerror',e=>errors.push(e.message));
    try{
      await p.addInitScript(s=>{window.mockState=s;window.smsRequests=[];window.mockLogs=[];window.retryRequests=[];window.NativeAttendance={readState:()=>JSON.stringify({ok:true,data:JSON.stringify(window.mockState)}),saveState:raw=>{if(window.failSave)return JSON.stringify({ok:false,error:'저장 테스트 실패'});window.mockState=JSON.parse(raw);return JSON.stringify({ok:true});},deviceInfo:()=>JSON.stringify({smsGranted:true,smsCapable:true,simReady:true}),smsLogs:()=>JSON.stringify({ok:true,jobs:window.mockLogs}),retrySms:id=>window.retryRequests.push(id),sendAttendanceSms:raw=>window.smsRequests.push(raw)};},fixture());
      await p.clock.install({time:new Date('2026-10-15T01:00:00Z')});await p.goto(`http://127.0.0.1:${server.address().port}`);await run(p);assert.deepEqual(errors,[]);assert.deepEqual(await p.evaluate(()=>window.smsRequests),[]);console.log('PASS '+name);
    }catch(e){failures.push(name+': '+e.message);}finally{await p.close();}
  }
  await check('changing a draft guardian number cannot enable SMS for the old number',async p=>{
    await edit(p);await p.locator('[name=g-phone-0]').fill('01099998888');await p.locator('[name=g-notify-0]').check();
    assert.equal((await read(p)).students[0].guardians[0].notify,false,'old number must not be enabled by the new number checkbox');
    assert.equal((await read(p)).students[0].guardians[0].phone,'01011112222');
    assert.match(await p.locator('[data-guardian-save-state]').textContent(),/변경 저장/);
    await p.locator('[aria-label=닫기]').click();await p.reload();assert.equal((await read(p)).students[0].guardians[0].notify,false);
  });
  await check('formatting the same number preserves immediate checkbox saving',async p=>{
    await edit(p);await p.locator('[name=g-phone-0]').fill('+82 10-1111-2222');await p.locator('[name=name]').fill('아직 저장하지 않은 이름');await p.locator('[name=g-notify-0]').check();
    assert.equal((await read(p)).students[0].guardians[0].notify,true);assert.equal((await read(p)).students[0].name,'김하늘');assert.equal(await p.locator('[name=name]').inputValue(),'아직 저장하지 않은 이름');
  });
  await check('new guardian number and consent commit together after a failed save is retried',async p=>{
    await edit(p);await p.locator('[name=g-phone-0]').fill('01099998888');await p.locator('[name=g-notify-0]').check();await p.evaluate(()=>window.failSave=true);await p.getByRole('button',{name:'변경 저장',exact:true}).click();
    assert.equal((await read(p)).students[0].guardians[0].notify,false);assert.equal((await read(p)).students[0].guardians[0].phone,'01011112222');assert.equal(await p.locator('[name=g-phone-0]').inputValue(),'01099998888');assert.equal(await p.locator('[name=g-notify-0]').isChecked(),true);
    await p.evaluate(()=>window.failSave=false);await p.getByRole('button',{name:'변경 저장',exact:true}).click();assert.equal((await read(p)).students[0].guardians[0].phone,'01099998888');assert.equal((await read(p)).students[0].guardians[0].notify,true);
  });
  await check('a delayed backup read keeps the student draft and waits before showing restore consent',async p=>{
    await edit(p);await p.locator('[name=name]').fill('입력 중인 학생 이름');
    const backup=fixture();backup.settings.academy='복원할 학원';backup.settings.sms.enabled=true;
    await p.evaluate(raw=>window.AttendanceApp.onImport(raw),C.backup(backup));
    assert.equal(await p.locator('[name=name]').inputValue(),'입력 중인 학생 이름','backup completion must not replace an editing form');assert.equal(await p.locator('#restore-consent').count(),0);assert.equal((await read(p)).settings.academy,'우리 학원');
    await p.getByRole('button',{name:'변경 저장',exact:true}).click();await p.locator('#restore-consent').waitFor();assert.equal((await read(p)).students[0].name,'입력 중인 학생 이름');
    await p.locator('#restore-consent').check();await p.evaluate(()=>window.failSave=true);await p.locator('#restore-confirm').click();assert.equal((await read(p)).settings.academy,'우리 학원');assert.equal(await p.locator('#restore-confirm').isVisible(),true);
    await p.evaluate(()=>window.failSave=false);await p.locator('#restore-confirm').click();assert.equal((await read(p)).settings.academy,'복원할 학원');assert.equal((await read(p)).settings.sms.enabled,false);
  });
  await check('cancelling a deferred restore leaves data and settings untouched',async p=>{
    await edit(p);await p.locator('[name=memo]').fill('저장하지 않은 메모');const backup=fixture();backup.settings.academy='취소할 복원';
    await p.evaluate(raw=>window.AttendanceApp.onImport(raw),C.backup(backup));assert.equal(await p.locator('[name=memo]').inputValue(),'저장하지 않은 메모');await p.locator('[aria-label=닫기]').click();await p.locator('#restore-consent').waitFor();await p.getByRole('button',{name:'취소',exact:true}).click();assert.deepEqual(await read(p),fixture());assert.equal(await p.locator('.modal').count(),0);
  });
  await check('invalid delayed backups leave the current form open',async p=>{
    await edit(p);await p.locator('[name=memo]').fill('보존할 메모');await p.evaluate(()=>window.AttendanceApp.onImport('{bad'));assert.equal(await p.locator('[name=memo]').inputValue(),'보존할 메모');await p.locator('[aria-label=닫기]').click();assert.equal(await p.locator('.modal').count(),0);assert.deepEqual(await read(p),fixture());
  });
  await check('a dialog opened by a completed action is not replaced by the deferred restore',async p=>{
    await p.evaluate(()=>window.mockLogs=[{id:'job1',studentName:'김하늘',attendanceStatus:'present',guardianName:'보호자',phone:'01011112222',createdAt:Date.now(),date:'2026-10-15',parts:1,sentParts:0,failedParts:1,message:'모의 발송 기록',state:'failed',canRetry:true,error:'테스트 오류'}]);
    await p.locator('[data-tab=settings]').click();await p.locator('[data-contact-action=sms-log]').click();await p.locator('[data-contact-action=retry]').click();await p.evaluate(raw=>window.AttendanceApp.onImport(raw),C.backup(fixture()));
    await p.locator('#confirm-action').click();assert.equal(await p.locator('#sms-logs').count(),1);assert.equal(await p.locator('#restore-consent').count(),0);assert.deepEqual(await p.evaluate(()=>window.retryRequests),['job1']);
    await p.locator('[aria-label=닫기]').click();await p.locator('#restore-consent').waitFor();assert.deepEqual(await read(p),fixture());
  });
  await browser.close();server.close();assert.deepEqual(failures,[]);console.log('Interaction audit passed.');
}
main().catch(e=>{console.error(e);server.close();process.exit(1);});
