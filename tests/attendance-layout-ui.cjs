// Attendance-first navigation, Korean name search, and daily statistics.
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
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 const browser=await chromium.launch({headless:true,...(process.env.CHROMIUM_PATH?{executablePath:process.env.CHROMIUM_PATH}:{}),args:JSON.parse(process.env.CHROMIUM_ARGS_JSON||'[]')});
 const p=await browser.newPage({viewport:{width:390,height:844},locale:'ko-KR',timezoneId:'Asia/Seoul',bypassCSP:!!process.env.QA_FONT_DIR}),errors=[];
 p.on('pageerror',e=>errors.push(e.message));
 const data=C.fresh(),names=['정수아','박지우','김하늘','이서준','강민준','최도윤','김하율'];
 data.settings.academy='늘봄학원';
 data.students=names.map((name,i)=>({id:'s'+i,name,classId:C.DEFAULT_CLASS_ID,memo:'',phone:'',contactId:'',guardians:[],active:true,joinedDate:'2026-01-01',archivedDate:null}));
 for(const [id,status] of [['s0','excused'],['s1','absent'],['s2','present']])data.records.push({studentId:id,studentName:data.students.find(s=>s.id===id).name,classId:C.DEFAULT_CLASS_ID,className:'전체 학생',date:'2026-10-14',status,note:'',time:status==='present'?'09:00':'',updatedAt:'2026-10-14T00:00:00Z'});
 await p.addInitScript(s=>{if(!localStorage.getItem('oneul-attendance-v1'))localStorage.setItem('oneul-attendance-v1',JSON.stringify(s));},data);
 await p.clock.install({time:new Date('2026-10-14T00:30:00Z')});
 const url=`http://127.0.0.1:${server.address().port}`;await p.goto(url);
 assert.equal(await p.locator('.week-strip .day-pill').count(),7,'the single-row calendar stays on the first screen');
 assert.equal(await p.locator('.hero,.report-hero,.ring-wrap').count(),0,'attendance opens without statistics');
 assert.deepEqual(await p.locator('#attendance-list h3').allTextContents(),['강민준','김하늘','김하율','박지우','이서준','정수아','최도윤']);
 const first=await p.locator('.status-row').first().boundingBox();assert.ok(first.y+first.height<600,'first attendance controls should be visible without scrolling');
 await p.locator('#attendance-search').fill('ㄱㅎ');assert.deepEqual(await p.locator('#attendance-list h3').allTextContents(),['김하늘','김하율']);
 assert.equal(await p.locator('#attendance-result-count').textContent(),'검색 결과 2명');
 await p.locator('#attendance-search').fill('김 하');assert.equal(await p.locator('.attendance-card').count(),2);
 await p.locator('[data-action=bulk-present]').click();await p.locator('#confirm-action').click();
 let saved=await p.evaluate(()=>JSON.parse(localStorage.getItem('oneul-attendance-v1')));
 assert.equal(saved.records.length,4);assert.equal(saved.records.find(r=>r.studentId==='s1').status,'absent','filtered bulk must leave nonmatching students alone');
 await p.locator('[data-action=undo]').click();
 await p.locator('[data-action=search-clear]').click();assert.equal(await p.locator('.attendance-card').count(),7);assert.equal(await p.evaluate(()=>document.activeElement.id),'attendance-search');
 await p.locator('#attendance-search').fill('없는학생');assert.equal(await p.locator('.attendance-card').count(),0);
 await p.locator('#attendance-search').fill('서준');await p.locator('[data-action=mark][data-status=present]').click();
 await p.reload();assert.equal(await p.locator('[data-student=s3] .present').getAttribute('aria-pressed'),'true');
 await p.locator('[data-tab=reports]').click();assert.equal(await p.locator('.ring-wrap').getAttribute('aria-label'),'출석률 67%');assert.equal(await p.locator('.report-big').textContent(),'67%');
 assert.equal(await p.locator('.daily-progress').textContent(),'체크 완료 4명 / 7명');
 await p.locator('#summary-date').fill('2026-10-13');await p.locator('#summary-date').dispatchEvent('change');assert.equal(await p.locator('.ring-wrap').getAttribute('aria-label'),'출석률 기록 없음');
 assert.equal(await p.locator('.report-big').textContent(),'67%','daily selection must not change the monthly report');
 await p.locator('[data-action=summary-open]').click();assert.equal(await p.locator('#attendance-date').inputValue(),'2026-10-13');assert.equal(await p.locator('.status-button.selected').count(),0);
 await p.locator('[data-action=today]').click();
 await p.locator('[data-tab=students]').click();await p.locator('#student-search').fill('ㄱㅎ');assert.equal(await p.locator('#student-list .student-card').count(),2);await p.locator('[data-action=search-clear]').click();assert.equal(await p.locator('#student-list .student-card').count(),7);
 if(process.env.QA_FONT_DIR){await p.addStyleTag({url:url+'/qa-font/400.css'});await p.addStyleTag({url:url+'/qa-font/700.css'});await p.evaluate(()=>document.fonts.ready);}
 for(const theme of ['light','dark']){
  await p.evaluate(mode=>{const s=window.AttendanceApp.getState();window.AttendanceApp.save({...s,settings:{...s.settings,theme:mode}},'');},theme);
  for(const width of [320,390,800]){
   await p.setViewportSize({width,height:844});
   for(const tab of ['attendance','reports']){
    await p.locator(`[data-tab=${tab}]`).click();
    assert.equal(await p.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false,`${theme} ${tab} overflow at ${width}`);
    if(tab==='attendance'){
     for(const b of await p.locator('.attendance-card').first().locator('.status-button').all()){assert.ok((await b.boundingBox()).height>=44);}
     await p.evaluate(()=>window.scrollTo(0,500));assert.ok((await p.locator('#attendance-search').boundingBox()).y>=0,'search remains on screen while scrolling');await p.evaluate(()=>window.scrollTo(0,0));
    }
    if(process.env.QA_SCREENSHOT_DIR&&width===390){fs.mkdirSync(process.env.QA_SCREENSHOT_DIR,{recursive:true});await p.screenshot({path:path.join(process.env.QA_SCREENSHOT_DIR,`${tab}-${theme}.png`),fullPage:tab==='reports'});}
   }
  }
 }
 assert.deepEqual(errors,[]);await browser.close();server.close();console.log('Attendance layout passed: first screen, calendar, Korean sorting/search, filtered bulk, daily/monthly stats, persistence, touch targets, sticky search, two themes and three widths.');
}
main().catch(e=>{console.error(e);server.close();process.exit(1);});
