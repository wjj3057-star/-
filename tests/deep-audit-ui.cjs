// Slow-provider and large-contact regressions; no device data or SMS are used.
const {chromium}=require('playwright');
const assert=require('node:assert/strict'),http=require('node:http'),fs=require('node:fs'),path=require('node:path');
const C=require('../app/src/main/assets/core.js'),assets=path.resolve(__dirname,'../app/src/main/assets');
const server=http.createServer((req,res)=>{const n=new URL(req.url,'http://localhost').pathname,font=n.startsWith('/qa-font/')&&process.env.QA_FONT_DIR,base=font?path.resolve(process.env.QA_FONT_DIR):assets,f=path.resolve(base,font?n.slice(9):n==='/'?'index.html':'.'+n);if(!f.startsWith(base+path.sep)||!fs.existsSync(f)||fs.statSync(f).isDirectory()){res.writeHead(404);res.end();return;}res.setHeader('Content-Type',f.endsWith('.css')?'text/css':f.endsWith('.js')?'application/javascript':f.endsWith('.woff2')?'font/woff2':'text/html; charset=utf-8');res.end(fs.readFileSync(f));});
const student=i=>({id:'s'+i,name:'학생'+String(i).padStart(3,'0'),classId:C.DEFAULT_CLASS_ID,memo:'',phone:'',contactId:'',guardians:[],active:true,joinedDate:'2026-01-01',archivedDate:null});
async function main(){
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const url=`http://127.0.0.1:${server.address().port}`;
 const browser=await chromium.launch({headless:true,...(process.env.CHROMIUM_PATH?{executablePath:process.env.CHROMIUM_PATH}:{}),args:JSON.parse(process.env.CHROMIUM_ARGS_JSON||'[]')});
 const failures=[];
 async function check(name,fixture,run){
  const p=await browser.newPage({viewport:{width:390,height:844},locale:'ko-KR',timezoneId:'Asia/Seoul',bypassCSP:!!process.env.QA_FONT_DIR}),errors=[];p.on('pageerror',e=>errors.push(e.message));
  await p.addInitScript(s=>{
   window.testState=s;window.testContacts=[{id:'old',name:'이전 학생',phone:'01011112222'}];window.holdContacts=false;window.pendingContacts='';
   window.NativeAttendance={ready:()=>{window.readyCalled=!!window.ContactSms&&!!window.AttendanceApp;},readState:()=>JSON.stringify({ok:true,data:JSON.stringify(window.testState)}),saveState:raw=>{window.testState=JSON.parse(raw);return JSON.stringify({ok:true});},deviceInfo:()=>JSON.stringify({contactsGranted:true,smsGranted:true,simReady:true,smsCapable:true}),smsLogs:()=>JSON.stringify({ok:true,jobs:[]}),loadContacts:id=>{window.pendingContacts=id;if(!window.holdContacts)setTimeout(()=>window.ContactSms.onContacts({requestId:id,ok:true,contacts:window.testContacts}),0);}};
  },fixture);await p.clock.install({time:new Date('2026-10-12T07:00:00Z')});await p.goto(url);if(process.env.QA_FONT_DIR){await p.addStyleTag({url:url+'/qa-font/400.css'});await p.addStyleTag({url:url+'/qa-font/700.css'});await p.evaluate(()=>document.fonts.ready);}
  try{assert.equal(await p.evaluate(()=>window.readyCalled),true,'native bridge notified after both modules are ready');await run(p);assert.deepEqual(errors,[]);console.log('PASS '+name);}catch(e){failures.push(name+': '+e.message);}finally{await p.close();}
 }
 await check('a pending contact reload never exposes a prior address book',C.fresh(),async p=>{
  await p.locator('[data-action=student-add]').first().click();await p.locator('[name=name]').fill('작성 중');await p.locator('[data-target=student]').click();await p.locator('[data-contact-action=contact-select]').first().waitFor();await p.locator('[data-contact-action=picker-back]').click();
  await p.evaluate(()=>window.holdContacts=true);await p.locator('[data-target=student]').click();await p.locator('#contact-search').fill('이전');
  assert.equal(await p.locator('[data-contact-action=contact-select]').count(),0,'stale contacts were selectable before the new provider response');
  await p.evaluate(()=>window.ContactSms.onContacts({requestId:window.pendingContacts,ok:true,contacts:[{id:'new',name:'새 학생',phone:'01033334444'}]}));
  await p.locator('#contact-search').fill('새');await p.locator('[data-contact-action=contact-select]').click();assert.equal(await p.locator('[name=phone]').inputValue(),'01033334444');assert.equal(await p.locator('[name=name]').inputValue(),'작성 중');
 });
 const large=C.fresh();large.students=Array.from({length:450},(_,i)=>student(i));
 await check('large automatic registration remains bounded and preserves page edits',large,async p=>{
  await p.evaluate(()=>window.testContacts=Array.from({length:180},(_,i)=>({id:String(i),name:'학생'+String(i).padStart(3,'0')+' 어머니',phone:'010'+String(10000000+i)})));
  await p.locator('[data-tab=settings]').click();await p.locator('[data-contact-action=auto-register]').click();await p.locator('[data-contact-action=scan]').click();await p.locator('.candidate-card').first().waitFor();
  const mounted=await p.locator('#candidate-form option').count();console.log('Large import mounted options: '+mounted);assert.ok(mounted<10000,'large import mounted '+mounted+' options at once');
  await p.locator('[data-target-search="0"]').fill('학생449');await p.locator('[data-target-search="0"]').press('Enter');assert.equal(await p.locator('#candidate-form').count(),1,'search Enter must not submit the registration');
  assert.equal(await p.locator('[name=target-0] option[value=s449]').count(),1,'search reaches students beyond the initial choices');
  await p.locator('[data-target-search="0"]').fill('');
  await p.locator('[name=name-0]').fill('수정한 이름');await p.locator('[name=guardian-0-0]').uncheck();
  await p.locator('[data-contact-action=candidates-next]').click();await p.locator('[data-contact-action=candidates-prev]').click();
  assert.equal(await p.locator('[name=name-0]').inputValue(),'수정한 이름');assert.equal(await p.locator('[name=guardian-0-0]').isChecked(),false);
  await p.setViewportSize({width:320,height:740});assert.equal(await p.evaluate(()=>document.querySelector('.modal').scrollWidth>document.querySelector('.modal').clientWidth+1),false,'pagination fits a narrow screen');
  if(process.env.SCREENSHOT_DIR){fs.mkdirSync(process.env.SCREENSHOT_DIR,{recursive:true});await p.setViewportSize({width:390,height:844});await p.evaluate(()=>document.querySelector('.modal').scrollTop=0);await p.screenshot({path:path.join(process.env.SCREENSHOT_DIR,'registration-light.png')});await p.emulateMedia({colorScheme:'dark'});await p.waitForFunction(()=>document.documentElement.dataset.theme==='dark');await p.screenshot({path:path.join(process.env.SCREENSHOT_DIR,'registration-dark.png')});}
  await p.getByRole('button',{name:'선택한 학생 등록',exact:true}).click();
  const saved=await p.evaluate(()=>window.testState);assert.equal(saved.students.length,450);assert.equal(saved.students[0].guardians.length,0);assert.equal(saved.students[179].guardians.length,1,'hidden pages participate in the explicitly selected import');
 });
 await browser.close();server.close();assert.deepEqual(failures,[]);console.log('Deep UI passed: delayed contacts, bounded import and draft preservation.');
}
main().catch(e=>{console.error(e);server.close();process.exit(1);});
