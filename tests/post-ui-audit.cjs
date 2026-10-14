const {chromium}=require('playwright');
const assert=require('node:assert/strict'),http=require('node:http'),fs=require('node:fs'),path=require('node:path');
const C=require('../app/src/main/assets/core.js'),assets=path.resolve(__dirname,'../app/src/main/assets');
const server=http.createServer((req,res)=>{const n=new URL(req.url,'http://localhost').pathname,f=path.resolve(assets,n==='/'?'index.html':'.'+n);if(!f.startsWith(assets+path.sep)||!fs.existsSync(f)){res.writeHead(404);res.end();return;}res.setHeader('Content-Type',f.endsWith('.css')?'text/css':f.endsWith('.js')?'application/javascript':'text/html; charset=utf-8');res.end(fs.readFileSync(f));});
const fixture=()=>{const s=C.fresh();s.students=['김하늘','박지우','최민수'].map((name,i)=>({id:'s'+i,name,classId:C.DEFAULT_CLASS_ID,memo:'',phone:'',contactId:'',guardians:[],active:true,joinedDate:'2026-01-01',archivedDate:null}));return C.mark(s,'s0','2026-10-14','present','기존 메모',new Date(2026,9,14,9,0));};
async function main(){
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 const browser=await chromium.launch({headless:true,...(process.env.CHROMIUM_PATH?{executablePath:process.env.CHROMIUM_PATH}:{}),args:JSON.parse(process.env.CHROMIUM_ARGS_JSON||'[]')}),failures=[];
 async function check(name,run){const p=await browser.newPage({viewport:{width:390,height:844},locale:'ko-KR',timezoneId:'Asia/Seoul'}),errors=[];p.on('pageerror',e=>errors.push(e.message));
  try{await p.addInitScript(s=>{window.mockState=s;window.NativeAttendance={readState:()=>JSON.stringify({ok:true,data:JSON.stringify(window.mockState)}),saveState:raw=>{if(window.failSave)return JSON.stringify({ok:false,error:'저장 테스트 실패'});window.mockState=JSON.parse(raw);return JSON.stringify({ok:true});},deviceInfo:()=>JSON.stringify({smsGranted:true,smsCapable:true,simReady:true}),smsLogs:()=>JSON.stringify({ok:true,jobs:[]}),sendAttendanceSms:()=>{}};},fixture());await p.clock.install({time:new Date('2026-10-14T00:30:00Z')});await p.goto(`http://127.0.0.1:${server.address().port}`);await run(p);assert.deepEqual(errors,[]);console.log('PASS '+name);}catch(e){failures.push(name+': '+e.message);}finally{await p.close();}
 }
 await check('renamed students remain searchable and sorted without rewriting history',async p=>{
  await p.locator('[data-action=student-detail][data-id=s0]').click();await p.locator('[data-action=student-edit]').click();await p.locator('[name=name]').fill('한가람');await p.getByRole('button',{name:'변경 저장',exact:true}).click();
  await p.locator('#attendance-search').fill('한가람');assert.equal(await p.locator('[data-student=s0]').count(),1,'new name must find an already checked student');
  assert.equal(await p.locator('[data-student=s0] h3').textContent(),'한가람');
  assert.equal(await p.evaluate(()=>window.mockState.records[0].studentName),'김하늘','historical snapshot must remain intact');
  await p.locator('#attendance-search').fill('김하늘');assert.equal(await p.locator('[data-student=s0]').count(),1,'old recorded name remains a search alias');
  await p.locator('[data-action=search-clear]').click();assert.deepEqual(await p.locator('.attendance-card h3').allTextContents(),['박지우','최민수','한가람']);
 });
 await check('attendance save retains keyboard focus on the same student and action',async p=>{
  await p.locator('[data-id=s1][data-action=mark][data-status=present]').focus();await p.keyboard.press('Enter');
  assert.deepEqual(await p.evaluate(()=>({id:document.activeElement.dataset.id,status:document.activeElement.dataset.status})),{id:'s1',status:'present'});
  await p.keyboard.press('Tab');assert.equal(await p.evaluate(()=>document.activeElement.dataset.status),'late');
 });
 await check('a later auto-save removes the unavailable undo action',async p=>{
  await p.locator('[data-id=s1][data-action=mark][data-status=present]').click();assert.equal(await p.locator('[data-action=undo]').count(),1);
  await p.locator('[data-tab=settings]').click();await p.locator('[data-contact-action=sms-settings]').click();await p.locator('[name=statuses][value=absent]').check();
  assert.equal(await p.locator('[data-action=undo]').count(),0,'old undo must not remain as a dead button');
 });
 await check('failed setting save keeps the attendance undo available',async p=>{
  await p.locator('[data-id=s1][data-action=mark][data-status=present]').click();await p.locator('[data-tab=settings]').click();await p.locator('[data-contact-action=sms-settings]').click();await p.evaluate(()=>window.failSave=true);await p.locator('[name=statuses][value=absent]').click();assert.equal(await p.locator('[name=statuses][value=absent]').isChecked(),false);
  assert.equal(await p.locator('[data-action=undo]').count(),1);await p.evaluate(()=>window.failSave=false);await p.locator('[aria-label=닫기]').click();await p.locator('[data-action=undo]').click();assert.equal(await p.evaluate(()=>window.mockState.records.length),1);
 });
 await browser.close();server.close();assert.deepEqual(failures,[]);console.log('Post UI audit passed.');
}
main().catch(e=>{console.error(e);server.close();process.exit(1);});
