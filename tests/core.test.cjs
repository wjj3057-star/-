const test = require('node:test');
const assert = require('node:assert/strict');
const C = require('../app/src/main/assets/core.js');
function fixture() {
  const s=C.fresh();
  s.students=[
    {id:'s1',name:'김하늘',classId:C.DEFAULT_CLASS_ID,memo:'',phone:'',contactId:'',guardians:[],active:true,joinedDate:'2026-10-01',archivedDate:null},
    {id:'s2',name:'박지우',classId:C.DEFAULT_CLASS_ID,memo:'',phone:'',contactId:'',guardians:[],active:true,joinedDate:'2026-10-01',archivedDate:null}
  ];
  return C.validate(s);
}
const now=new Date(2026,9,8,16,17);
test('local date validation and midnight boundary',()=>{
  assert.equal(C.validDate('2026-02-30'),false);
  assert.equal(C.validDate('2024-02-29'),true);
  assert.equal(C.shiftDate('2026-10-08',1),'2026-10-09');
  assert.equal(C.localDate(new Date(2026,9,9,0,0)),'2026-10-09');
});
test('all active students appear every day with unchecked status, independent of weekday',()=>{
  const s=fixture();assert.equal(C.roster(s,'2026-10-08').length,2);
  assert.equal(C.roster(s,'2026-10-10').length,2);
  assert.equal(C.roster(s,'2026-09-30').length,0);
  assert.equal(C.count(C.roster(s,'2026-10-08')).unmarked,2);
  assert.equal(C.LABELS.unmarked,'미출석');
});
test('midnight reopens every student as 미출석 while yesterday is intact',()=>{
  let s=fixture();
  s=C.mark(s,'s1','2026-10-08','present','',now);
  s=C.mark(s,'s2','2026-10-08','late','',now);
  assert.equal(C.count(C.roster(s,'2026-10-08')).unmarked,0);
  const nextDay=C.roster(s,'2026-10-09');
  assert.equal(C.count(nextDay).unmarked,2);
  assert.equal(C.recordOf(s,'s1','2026-10-08').status,'present');
  assert.equal(C.recordOf(s,'s1','2026-10-09'),undefined);
  assert.equal(C.report(s,'2026-10').totals.present,1);
});
test('mark is immutable, toggle to unchecked removes only selected date',()=>{
  const s=fixture(),marked=C.mark(s,'s1','2026-10-08','present',undefined,now);
  assert.equal(s.records.length,0);
  assert.equal(marked.records[0].time,'16:17');
  const late=C.mark(marked,'s1','2026-10-08','late','병원',now);
  assert.equal(late.records.length,1);
  assert.equal(late.records[0].note,'병원');
  assert.equal(C.mark(late,'s1','2026-10-08','unmarked',undefined,now).records.length,0);
});
test('backdated records have no invented check-in time, future writes rejected',()=>{
  assert.equal(C.mark(fixture(),'s1','2026-10-07','present','',now).records[0].time,'');
  assert.throws(()=>C.mark(fixture(),'s1','2026-10-09','present','',now));
  assert.throws(()=>C.mark(fixture(),'s1','2026-09-30','present','',now));
});
test('legacy class data are consolidated but historic attendance snapshot is retained',()=>{
  const s=fixture();
  s.classes=[{id:'math',name:'수학반',days:[1],time:'16:00',color:'#8974C9',archived:false}];
  s.students[0].classId='math';s.students[1].classId='math';
  s.records=[{studentId:'s1',classId:'math',date:'2026-10-08',status:'present',note:'',studentName:'김하늘',className:'수학반',time:'16:17',updatedAt:now.toISOString()}];
  const migrated=C.validate(s);
  assert.equal(migrated.students[0].classId,C.DEFAULT_CLASS_ID);
  assert.equal(migrated.classes.find(c=>c.id==='math').name,'수학반');
  assert.equal(C.roster(migrated,'2026-10-08')[0].record.className,'수학반');
  assert.equal(C.roster(migrated,'2026-10-09').length,2);
});
test('legacy v1 data imports into single student list without enabling SMS',()=>{
  const s=fixture();s.schema=1;delete s.settings.sms;delete s.settings.contactKeywords;
  s.classes=[{id:'b',name:'영어반',days:[6],time:'11:00',color:'#529E99',archived:false}];
  s.students.forEach(st=>{st.classId='b';delete st.phone;delete st.contactId;delete st.guardians;});
  const next=C.validate(s);
  assert.equal(next.schema,2);
  assert.equal(next.students.length,2);
  assert.equal(next.settings.sms.enabled,false);
  assert.equal(C.roster(next,'2026-10-08').length,2);
});
test('archived students stay in old reports and are not visible next day',()=>{
  let s=C.mark(fixture(),'s1','2026-10-08','present','',now);
  s.students[0].active=false;s.students[0].archivedDate='2026-10-09';
  assert.equal(C.roster(s,'2026-10-08').length,2);
  assert.equal(C.roster(s,'2026-10-09').length,1);
  assert.equal(C.report(s,'2026-10').totals.present,1);
});
test('monthly rates count late as attendance but exclude excused and unchecked',()=>{
  let s=fixture();for(const [date,status] of [['2026-10-05','present'],['2026-10-06','late'],['2026-10-07','absent'],['2026-10-08','excused']])s=C.mark(s,'s1',date,status,'',now);
  assert.equal(C.report(s,'2026-10').rate,67);
  assert.equal(C.report(s,'2026-10').totals.excused,1);
  assert.equal(C.report(s,'2026-09').rate,null);
});
test('backup roundtrip and malformed data rejection',()=>{
  const s=C.mark(fixture(),'s1','2026-10-08','present','안녕',now);
  assert.deepEqual(C.parseBackup(C.backup(s)),C.validate(s));
  assert.throws(()=>C.parseBackup('{bad'));
  const broken=C.clone(s);broken.records.push(C.clone(broken.records[0]));assert.throws(()=>C.validate(broken));
  broken.records.pop();broken.records[0].studentId='missing';assert.throws(()=>C.validate(broken));
});
test('CSV escapes formulas and preserves Korean/newlines/quotes',()=>{
  const s=C.mark(fixture(),'s1','2026-10-08','present','줄1\n"줄2"',now);
  s.records[0].studentName='=HYPERLINK("bad")';const csv=C.csv(s.records);
  assert.ok(csv.startsWith('\uFEFF'));
  assert.ok(csv.includes('"\'=HYPERLINK(""bad"")"'));
  assert.ok(csv.includes('줄1\n""줄2""'));
});
