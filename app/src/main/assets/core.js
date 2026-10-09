(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.AttendanceCore = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';
  const STATUSES = ['present', 'late', 'absent', 'excused'];
  const LABELS = {present: '출석', late: '지각', absent: '결석', excused: '공결', unmarked: '미출석'};
  const clone = value => JSON.parse(JSON.stringify(value));
  function localDate(date = new Date()) {
    return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
  }
  function validDate(value) {
    if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
    const d = new Date(value + 'T12:00:00');
    return Number.isFinite(d.getTime()) && localDate(d) === value && value >= '2000-01-01' && value <= '2100-12-31';
  }
  function shiftDate(value, amount) {
    const d = new Date(value + 'T12:00:00'); d.setDate(d.getDate() + amount); return localDate(d);
  }
  function uid() {
    if (typeof crypto !== 'undefined' && crypto.getRandomValues) {
      return 'id_' + Array.from(crypto.getRandomValues(new Uint32Array(4)), x => x.toString(16).padStart(8, '0')).join('');
    }
    return 'id_' + Date.now().toString(36) + Math.random().toString(36).slice(2);
  }
  const DEFAULT_TEMPLATE = '[{학원명}] {학생이름} 학생이 {날짜} {시간}에 {상태} 처리되었습니다.';
  const DEFAULT_KEYWORDS = {mother:['어머니','어머님','엄마','모친'],father:['아버지','아버님','아빠','부친'],guardian:['보호자'],student:['학생']};
  const TOKENS = ['학원명','학생이름','반이름','날짜','시간','상태','선생님','메모'];
  // The single internal group is a compatibility key, not a user-facing class.
  // Old class rows remain stored solely so historical attendance references still validate.
  const DEFAULT_CLASS_ID = '_all_students_';
  function singleClass() { return {id:DEFAULT_CLASS_ID,name:'전체 학생',color:'#8974C9',days:[0,1,2,3,4,5,6],time:'00:00',archived:false}; }
  function fresh() { return {schema: 2, settings: {academy: '우리 학원', teacher: '',sms:{enabled:false,statuses:['present','late'],template:DEFAULT_TEMPLATE},contactKeywords:clone(DEFAULT_KEYWORDS)}, classes: [singleClass()], students: [], records: []}; }
  function normalizePhone(value) {
    if (typeof value !== 'string' || !/^[+\d\s().-]*$/.test(value)) return '';
    let n=value.replace(/[\s().-]/g,'');
    if(n.startsWith('+82'))n='0'+n.slice(3).replace(/^0/,'');
    return /^\+?[0-9]{8,15}$/.test(n)?n:'';
  }
  function upgrade(value) {
    if(!value || value.schema!==1)return value;
    const next=clone(value);next.schema=2;
    next.settings={...next.settings,sms:fresh().settings.sms,contactKeywords:clone(DEFAULT_KEYWORDS)};
    if(Array.isArray(next.students))next.students=next.students.map(s=>({...s,phone:'',contactId:'',guardians:[]}));
    return next;
  }
  function text(value, max, required = false) {
    return typeof value === 'string' && value.length <= max && (!required || value.trim().length > 0);
  }
  function validate(value) {
    value=upgrade(value);
    // Migrate legacy multi-class data without deleting students or dated records.
    if (value && value.schema === 2 && Array.isArray(value.classes) && Array.isArray(value.students)) {
      value=clone(value);
      if (!value.classes.some(c=>c && c.id===DEFAULT_CLASS_ID)) value.classes.push(singleClass());
      else value.classes=value.classes.map(c=>c && c.id===DEFAULT_CLASS_ID?singleClass():c);
      value.students.forEach(s=>{if(s && typeof s==='object')s.classId=DEFAULT_CLASS_ID;});
    }
    const fail = () => { throw new Error('올바른 오늘출석 백업 파일이 아닙니다. 원래 데이터는 유지됩니다.'); };
    if (!value || value.schema !== 2 || !value.settings || !text(value.settings.academy, 40, true) || !text(value.settings.teacher, 30)) fail();
    const sms=value.settings.sms,kw=value.settings.contactKeywords;
    if(!sms||typeof sms.enabled!=='boolean'||!Array.isArray(sms.statuses)||sms.statuses.length>4||!sms.statuses.length||new Set(sms.statuses).size!==sms.statuses.length||sms.statuses.some(s=>!STATUSES.includes(s))||!text(sms.template,500,true)||!kw)fail();
    for(const role of Object.keys(DEFAULT_KEYWORDS))if(!Array.isArray(kw[role])||kw[role].length>20||!kw[role].length||kw[role].some(k=>!text(k,20,true)))fail();
    const allKeywords=Object.values(kw).flat().map(k=>k.trim());if(new Set(allKeywords).size!==allKeywords.length)fail();
    if (!Array.isArray(value.classes) || value.classes.length > 201 || !Array.isArray(value.students) || value.students.length > 5000 || !Array.isArray(value.records) || value.records.length > 100000) fail();
    const ids = new Set(), studentIds = new Set(), keys = new Set();
    const idOK = x => typeof x === 'string' && /^[a-zA-Z0-9_-]{1,80}$/.test(x);
    for (const c of value.classes) {
      if (!c || !idOK(c.id) || ids.has(c.id) || !text(c.name, 30, true) || !/^#[0-9a-fA-F]{6}$/.test(c.color) || typeof c.archived !== 'boolean' || !Array.isArray(c.days) || c.days.length < 1 || c.days.length > 7 || new Set(c.days).size !== c.days.length || c.days.some(x => !Number.isInteger(x) || x < 0 || x > 6) || !/^([01]\d|2[0-3]):[0-5]\d$/.test(c.time)) fail();
      ids.add(c.id);
    }
    for (const s of value.students) {
      if (!s || !idOK(s.id) || studentIds.has(s.id) || !text(s.name, 30, true) || !ids.has(s.classId) || !text(s.memo, 200) || typeof s.active !== 'boolean' || !validDate(s.joinedDate) || !(s.archivedDate === null || validDate(s.archivedDate))) fail();
      studentIds.add(s.id);
      if(!text(s.phone,30)||s.phone&&!normalizePhone(s.phone)||!text(s.contactId,100)||!Array.isArray(s.guardians)||s.guardians.length>6)fail();
      const phones=new Set(),guardianIds=new Set();
      for(const g of s.guardians){const phone=g&&normalizePhone(g.phone);if(!g||!idOK(g.id)||guardianIds.has(g.id)||!text(g.name,80,true)||!['mother','father','guardian'].includes(g.relation)||!phone||phones.has(phone)||typeof g.notify!=='boolean'||!text(g.contactId,100))fail();phones.add(phone);guardianIds.add(g.id);}
    }
    for (const r of value.records) {
      const key = r && `${r.date}|${r.studentId}`;
      if (!r || !studentIds.has(r.studentId) || !ids.has(r.classId) || !validDate(r.date) || !STATUSES.includes(r.status) || !text(r.note, 200) || !text(r.studentName, 30, true) || !text(r.className, 30, true) || !text(r.time, 5) || (r.time !== '' && !/^([01]\d|2[0-3]):[0-5]\d$/.test(r.time)) || typeof r.updatedAt !== 'string' || !Number.isFinite(Date.parse(r.updatedAt)) || keys.has(key)) fail();
      keys.add(key);
    }
    // Copy only known fields: never import prototypes, transient UI state or unrecognized settings.
    return {schema: 2, settings: {academy: value.settings.academy.trim(), teacher: value.settings.teacher.trim(),sms:{enabled:sms.enabled,statuses:[...sms.statuses],template:sms.template},contactKeywords:Object.fromEntries(Object.keys(DEFAULT_KEYWORDS).map(k=>[k,kw[k].map(v=>v.trim())]))},
      classes: value.classes.map(c => ({id:c.id,name:c.name.trim(),color:c.color,days:[...c.days],time:c.time,archived:c.archived})),
      students: value.students.map(s => ({id:s.id,name:s.name.trim(),classId:s.classId,memo:s.memo,active:s.active,joinedDate:s.joinedDate,archivedDate:s.archivedDate,phone:normalizePhone(s.phone),contactId:s.contactId,guardians:s.guardians.map(g=>({id:g.id,name:g.name.trim(),relation:g.relation,phone:normalizePhone(g.phone),notify:g.notify,contactId:g.contactId}))})),
      records: value.records.map(r => ({studentId:r.studentId,classId:r.classId,date:r.date,status:r.status,note:r.note,studentName:r.studentName,className:r.className,time:r.time,updatedAt:r.updatedAt}))};
  }
  function classOf(state, id) { return state.classes.find(c => c.id === id); }
  function recordOf(state, id, date) { return state.records.find(r => r.studentId === id && r.date === date); }
  function roster(state, date, _classId = 'all', _scheduledOnly = true, query = '') {
    const records = new Map(state.records.filter(r => r.date === date).map(r => [r.studentId, r]));
    const needle = query.trim().toLocaleLowerCase();
    const group = classOf(state, DEFAULT_CLASS_ID) || singleClass();
    return state.students.map(s => {
      const r = records.get(s.id);
      const eligible = s.joinedDate <= date && (s.active || (s.archivedDate && date < s.archivedDate));
      if (!r && !eligible) return null;
      const name = r ? r.studentName : s.name;
      if (needle && !name.toLocaleLowerCase().includes(needle)) return null;
      return {student:s, group, record:r || null, name, groupName:''};
    }).filter(Boolean).sort((a,b) => a.name.localeCompare(b.name, 'ko'));
  }
  function count(rows) {
    const totals = {present:0,late:0,absent:0,excused:0,unmarked:0,total:rows.length};
    rows.forEach(row => { totals[row.record ? row.record.status : 'unmarked']++; }); return totals;
  }
  function mark(state, id, date, status, note, now = new Date()) {
    if (!validDate(date) || date > localDate(now)) throw new Error('미래 날짜에는 출석을 기록할 수 없습니다.');
    if (status !== 'unmarked' && !STATUSES.includes(status)) throw new Error('출석 상태를 확인해 주세요.');
    if (typeof note !== 'undefined' && !text(note, 200)) throw new Error('메모는 200자 이내로 입력해 주세요.');
    const s = state.students.find(x => x.id === id); if (!s) throw new Error('학생을 찾을 수 없습니다.');
    const old = recordOf(state,id,date);
    const c = classOf(state, old ? old.classId : s.classId); if (!c) throw new Error('학생 출석 정보를 찾을 수 없습니다.');
    if (!old && (date < s.joinedDate || (!s.active && (!s.archivedDate || date >= s.archivedDate)))) throw new Error('이 날짜에는 등록되어 있지 않은 학생입니다.');
    const next = clone(state);
    const ix = next.records.findIndex(r => r.studentId === id && r.date === date);
    if (status === 'unmarked') { if (ix >= 0) next.records.splice(ix, 1); return next; }
    const r = {studentId:id,classId:old ? old.classId : c.id,date,status,note:note === undefined ? (old ? old.note : '') : note,
      studentName:old ? old.studentName : s.name,className:old ? old.className : c.name,
      time:['present','late'].includes(status) ? (old && old.time ? old.time : date === localDate(now) ? `${String(now.getHours()).padStart(2,'0')}:${String(now.getMinutes()).padStart(2,'0')}` : '') : '',updatedAt:now.toISOString()};
    if (ix >= 0) next.records[ix] = r; else next.records.push(r); return next;
  }
  // One transaction for bulk check-in: validate all students first, then clone once.
  // Previously a full JSON clone was made per student, causing quadratic work.
  function markUnmarkedPresent(state, ids, date, now = new Date()) {
    if (!validDate(date) || date > localDate(now)) throw new Error('미래 날짜에는 출석을 기록할 수 없습니다.');
    if (!Array.isArray(ids) || ids.length > 5000 || new Set(ids).size !== ids.length) throw new Error('학생 목록을 확인해 주세요.');
    const students = new Map(state.students.map(s => [s.id, s]));
    const already = new Set(state.records.filter(r => r.date === date).map(r => r.studentId));
    const pending = [];
    for (const id of ids) {
      const student = students.get(id);
      if (!student) throw new Error('학생을 찾을 수 없습니다.');
      if (already.has(id)) continue;
      if (date < student.joinedDate || (!student.active && (!student.archivedDate || date >= student.archivedDate))) {
        throw new Error('이 날짜에는 등록되어 있지 않은 학생입니다.');
      }
      const group = classOf(state, student.classId);
      if (!group) throw new Error('학생 출석 정보를 찾을 수 없습니다.');
      pending.push({student,group});
    }
    const next = clone(state);
    const time = date === localDate(now) ? `${String(now.getHours()).padStart(2,'0')}:${String(now.getMinutes()).padStart(2,'0')}` : '';
    const updatedAt = now.toISOString();
    for (const {student,group} of pending) {
      next.records.push({studentId:student.id,classId:group.id,date,status:'present',note:'',
        studentName:student.name,className:group.name,time,updatedAt});
    }
    return next;
  }
  function report(state, month, classId = 'all') {
    const records = state.records.filter(r => r.date.startsWith(month + '-'));
    const totals = count(records.map(r => ({record:r})));
    const denom = totals.present + totals.late + totals.absent;
    return {records,totals,rate:denom ? Math.round((totals.present + totals.late) / denom * 100) : null};
  }
  function csvCell(value) {
    let s = String(value == null ? '' : value);
    if (/^[\s]*[=+\-@]/.test(s) || /^[\t\r\n]/.test(s)) s = "'" + s;
    return '"' + s.replace(/"/g, '""') + '"';
  }
  function csv(records) {
    return '\uFEFF' + [['날짜','학생','출석 상태','체크 시간','메모'],...records.slice().sort((a,b) => a.date.localeCompare(b.date) || a.studentName.localeCompare(b.studentName,'ko')).map(r => [r.date,r.studentName,LABELS[r.status],r.time,r.note])].map(row => row.map(csvCell).join(',')).join('\r\n');
  }
  function backup(state) { return JSON.stringify({app:'오늘출석',exportedAt:new Date().toISOString(),data:validate(state)},null,2); }
  function parseBackup(raw) {
    if (typeof raw !== 'string' || raw.length > 24 * 1024 * 1024) throw new Error('백업 파일이 너무 큽니다.');
    let value; try { value = JSON.parse(raw.replace(/^\uFEFF/,'')); } catch (_) { throw new Error('읽을 수 없는 백업 파일입니다.'); }
    if (!value || value.app !== '오늘출석' || !value.data) throw new Error('오늘출석에서 만든 JSON 백업 파일을 선택해 주세요.');
    return validate(value.data);
  }
  const nameKey=name=>String(name).normalize('NFC').replace(/\s+/g,'').toLocaleLowerCase();
  function parseContactName(name,keywords=DEFAULT_KEYWORDS) {
    const cleaned=String(name||'').trim();
    const entries=Object.entries(keywords).flatMap(([role,list])=>list.map(word=>({role,word}))).sort((a,b)=>b.word.length-a.word.length);
    for(const {role,word} of entries){
      const key=word.replace(/[.*+?^${}()|[\]\\]/g,'\\$&');
      const after=new RegExp('^(.*?)\\s*[(（\\[<]?\\s*'+key+'\\s*[)）\\]>]?$','u');
      const before=new RegExp('^[(（\\[<]?\\s*'+key+'\\s*[)）\\]>]?\\s+(.+)$','u');
      const match=cleaned.match(after)||cleaned.match(before);if(!match)continue;
      const studentName=match[1].replace(/^[\s(（\[<]+|[\s)）\]>]+$/g,'').trim();
      if(!studentName||studentName.length>30||/[\r\n]/.test(studentName))continue;
      return {studentName,role};
    }
    return null;
  }
  function contactCandidates(contacts,state,keywords=state.settings.contactKeywords) {
    const groups=new Map();
    for(const c of contacts){const p=parseContactName(c.name,keywords),phone=normalizePhone(c.phone);if(!p||!phone)continue;const key=nameKey(p.studentName);if(!groups.has(key))groups.set(key,{key,name:p.studentName,guardians:[],studentContacts:[],matches:[]});const group=groups.get(key);const target=p.role==='student'?group.studentContacts:group.guardians;if(!target.some(x=>x.phone===phone))target.push({name:String(c.name).slice(0,80),phone,contactId:String(c.id||''),relation:p.role});}
    for(const group of groups.values()){
      for(const c of contacts)if(nameKey(c.name)===group.key&&normalizePhone(c.phone)&&!group.studentContacts.some(s=>s.phone===normalizePhone(c.phone)))group.studentContacts.push({name:c.name,phone:normalizePhone(c.phone),contactId:String(c.id||''),relation:'student'});
      group.matches=state.students.filter(s=>nameKey(s.name)===group.key).map(s=>s.id);
    }
    return [...groups.values()].sort((a,b)=>a.name.localeCompare(b.name,'ko'));
  }
  function mergeCandidates(state,rows,_unusedClassId=DEFAULT_CLASS_ID,date=localDate()){
    const classId=DEFAULT_CLASS_ID;
    const next=validate(state);let added=0,updated=0;const targets=new Set();
    for(const row of rows){
      const name=String(row.name||'').trim();if(!name||name.length>30)throw new Error('학생 이름은 1~30자로 입력해 주세요.');
      if(row.targetId==='unresolved')throw new Error(name+' 학생의 연결 대상을 직접 선택해 주세요.');
      let s=row.targetId==='new'?null:next.students.find(s=>s.id===row.targetId);
      if(row.targetId!=='new'&&!s)throw new Error('연결할 학생을 찾지 못했어요.');
      const targetKey=s?s.id:'new:'+nameKey(name)+':'+classId;if(targets.has(targetKey))throw new Error('같은 학생이 두 번 선택됐어요. 등록 대상을 확인해 주세요.');targets.add(targetKey);
      if(!s){if(next.students.some(s=>s.active&&s.classId===classId&&nameKey(s.name)===nameKey(name)))throw new Error(name+' 학생이 이미 등록되어 있어요. 기존 학생 연결을 선택해 주세요.');s={id:uid(),name,classId,memo:'',active:true,joinedDate:date,archivedDate:null,phone:'',contactId:'',guardians:[]};next.students.push(s);added++;}else updated++;
      if(row.studentContact){const phone=normalizePhone(row.studentContact.phone);if(s.phone&&s.phone!==phone)throw new Error(name+' 학생의 기존 번호와 달라요. 학생 정보 수정에서 직접 변경해 주세요.');s.phone=phone;s.contactId=row.studentContact.contactId;}
      for(const g of row.guardians){const phone=normalizePhone(g.phone);if(!phone)throw new Error('학부모 번호를 확인해 주세요.');if(s.guardians.some(x=>normalizePhone(x.phone)===phone))continue;s.guardians.push({id:uid(),name:g.name,relation:g.relation,phone,contactId:g.contactId,notify:true});}
      if(s.guardians.length>6)throw new Error(name+' 학생의 학부모 연락처는 최대 6개까지 등록할 수 있어요.');
    }
    return {state:validate(next),added,updated};
  }
  function renderSms(state,record,template=state.settings.sms.template){
    const values={'학원명':state.settings.academy,'학생이름':record.studentName,'반이름':record.className,'날짜':record.date,'시간':record.time||new Date(record.updatedAt).toLocaleTimeString('en-GB',{hour:'2-digit',minute:'2-digit',hour12:false}),'상태':LABELS[record.status],'선생님':state.settings.teacher,'메모':record.note};
    return template.replace(/\{([^{}]+)\}/g,(full,key)=>Object.prototype.hasOwnProperty.call(values,key)?values[key]:full);
  }
  function smsChanges(before,after,today=localDate()){
    if(!after.settings.sms.enabled)return [];
    const prior=new Map(before.records.map(r=>[r.studentId+'|'+r.date,r]));
    return after.records.filter(r=>r.date===today&&after.settings.sms.statuses.includes(r.status)&&(!prior.has(r.studentId+'|'+r.date)||prior.get(r.studentId+'|'+r.date).status!==r.status)&&after.students.some(s=>s.id===r.studentId&&s.active&&s.guardians.some(g=>g.notify))).map(r=>({studentId:r.studentId,date:r.date,status:r.status,updatedAt:r.updatedAt}));
  }
  return {STATUSES,LABELS,DEFAULT_TEMPLATE,DEFAULT_KEYWORDS,DEFAULT_CLASS_ID,TOKENS,normalizePhone,nameKey,parseContactName,contactCandidates,mergeCandidates,renderSms,smsChanges,clone,localDate,validDate,shiftDate,uid,fresh,upgrade,validate,classOf,recordOf,roster,count,mark,markUnmarkedPresent,report,csv,csvCell,backup,parseBackup};
});
