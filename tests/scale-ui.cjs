// Synthetic large rosters: no contacts or SMS leave the browser.
const {chromium}=require('playwright');
const assert=require('node:assert/strict'),http=require('node:http'),fs=require('node:fs'),path=require('node:path');
const C=require('../app/src/main/assets/core.js'),assets=path.resolve(__dirname,'../app/src/main/assets');
const server=http.createServer((req,res)=>{const n=new URL(req.url,'http://localhost').pathname,f=path.resolve(assets,n==='/'?'index.html':'.'+n);if(!f.startsWith(assets+path.sep)||!fs.existsSync(f)){res.writeHead(404);res.end();return;}res.setHeader('Content-Type',f.endsWith('.css')?'text/css':f.endsWith('.js')?'application/javascript':'text/html; charset=utf-8');res.end(fs.readFileSync(f));});
const fixture=count=>{const s=C.fresh();s.students=Array.from({length:count},(_,i)=>({id:'s'+i,name:'학생'+String(i).padStart(4,'0'),classId:C.DEFAULT_CLASS_ID,memo:'',phone:'',contactId:'',guardians:[],active:true,joinedDate:'2026-01-01',archivedDate:null}));return s;};
async function main(){
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 const browser=await chromium.launch({headless:true,...(process.env.CHROMIUM_PATH?{executablePath:process.env.CHROMIUM_PATH}:{}),args:JSON.parse(process.env.CHROMIUM_ARGS_JSON||'[]')});
 const failures=[];
 async function check(name,s,run){const p=await browser.newPage({viewport:{width:320,height:740},locale:'ko-KR',timezoneId:'Asia/Seoul'}),errors=[];p.on('pageerror',e=>errors.push(e.message));
  await p.addInitScript(data=>{window.testState=data;window.NativeAttendance={readState:()=>JSON.stringify({ok:true,data:JSON.stringify(window.testState)}),saveState:raw=>{window.testState=JSON.parse(raw);return JSON.stringify({ok:true});},deviceInfo:()=>JSON.stringify({}),smsLogs:()=>JSON.stringify({ok:true,jobs:[]})};},s);
  try{await p.clock.install({time:new Date('2026-10-12T07:00:00Z')});await p.goto(`http://127.0.0.1:${server.address().port}`);await run(p);assert.deepEqual(errors,[]);console.log('PASS '+name);}catch(e){failures.push(name+': '+e.message);}finally{await p.close();}
 }
 await check('paged attendance preserves targets, search scope, bulk changes and undo',fixture(121),async p=>{
  assert.ok(await p.locator('#attendance-list .student-card').count()<=100,'large attendance list must bound mounted cards');
  await p.locator('[data-page-kind=attendancePage][data-amount="1"]').click();
  const id=await p.locator('#attendance-list .student-card').first().getAttribute('data-student');
  await p.locator('#attendance-list [data-status=late]').first().click();
  assert.equal(await p.evaluate(id=>window.testState.records.find(r=>r.studentId===id)?.status,id),'late');
  await p.locator('[data-action=bulk-present]').click();await p.locator('#confirm-action').click();
  assert.equal(await p.evaluate(()=>window.testState.records.length),121,'bulk must cover matching students on other pages');
  assert.equal(await p.evaluate(id=>window.testState.records.find(r=>r.studentId===id).status,id),'late','bulk leaves already marked students alone');
  await p.locator('[data-action=undo]').click();assert.equal(await p.evaluate(()=>window.testState.records.length),1);
  await p.locator('#attendance-search').fill('학생0120');assert.equal(await p.locator('#attendance-list .student-card').count(),1);
  await p.locator('[data-action=bulk-present]').click();await p.locator('#confirm-action').click();assert.equal(await p.evaluate(()=>window.testState.records.length),2,'filtered bulk never modifies nonmatching students');
  assert.equal(await p.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false,'pagination fits a 320px phone');
  await p.locator('[data-tab=students]').click();await p.locator('[data-page-kind=studentPage][data-amount="1"]').click();await p.locator('#student-search').fill('학생0000');assert.equal(await p.locator('#student-list .student-card').count(),1);
 });
 if(!process.env.QUICK_SCALE){
  const large=fixture(5000);large.records=large.students.map(s=>({studentId:s.id,studentName:s.name,classId:C.DEFAULT_CLASS_ID,className:'전체 학생',date:'2026-10-11',status:'present',note:'',time:'16:00',updatedAt:'2026-10-11T07:00:00Z'}));
  await check('maximum supported student count remains bounded in all list screens',large,async p=>{
   assert.ok(await p.locator('#attendance-list .student-card').count()<=100);
   await p.locator('#attendance-search').fill('학생4999');assert.equal(await p.locator('[data-student=s4999]').count(),1);
   await p.locator('[data-tab=students]').click();assert.ok(await p.locator('#student-list .student-card').count()<=100);
   await p.locator('[data-tab=reports]').click();assert.ok(await p.locator('.history-row').count()<=100);await p.getByText('5000회',{exact:true}).waitFor();
   await p.locator('[data-page-kind=reportPage][data-amount="1"]').click();assert.ok(await p.locator('.history-row').count()>0);
   const studentId=await p.locator('.history-row').first().getAttribute('data-id');await p.locator('.history-row').first().click();await p.locator('[data-action=detail-date]').click();
   assert.equal(await p.locator('#attendance-date').inputValue(),'2026-10-11');
   assert.equal(await p.locator(`[data-student="${studentId}"]`).count(),1,'opening history must reach that student, not an unrelated first page');
   assert.equal(await p.evaluate(()=>document.activeElement.dataset.id),studentId,'keyboard focus follows the requested student');
  });
 }
 await browser.close();server.close();assert.deepEqual(failures,[]);console.log('Scale UI passed.');
}
main().catch(e=>{console.error(e);server.close();process.exit(1);});
