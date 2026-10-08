(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.AttendanceCore = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';
  const STATUSES = ['present', 'late', 'absent', 'excused'];
  const LABELS = {present: '출석', late: '지각', absent: '결석', excused: '공결', unmarked: '미확인'};
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
  function fresh() { return {schema: 1, settings: {academy: '우리 학원', teacher: ''}, classes: [], students: [], records: []}; }
  function text(value, max, required = false) {
    return typeof value === 'string' && value.length <= max && (!required || value.trim().length > 0);
  }
  function validate(value) {
    const fail = () => { throw new Error('올바른 오늘출석 백업 파일이 아닙니다. 원래 데이터는 유지됩니다.'); };
    if (!value || value.schema !== 1 || !value.settings || !text(value.settings.academy, 40, true) || !text(value.settings.teacher, 30)) fail();
    if (!Array.isArray(value.classes) || value.classes.length > 200 || !Array.isArray(value.students) || value.students.length > 5000 || !Array.isArray(value.records) || value.records.length > 100000) fail();
    const ids = new Set(), studentIds = new Set(), keys = new Set();
    const idOK = x => typeof x === 'string' && /^[a-zA-Z0-9_-]{1,80}$/.test(x);
    for (const c of value.classes) {
      if (!c || !idOK(c.id) || ids.has(c.id) || !text(c.name, 30, true) || !/^#[0-9a-fA-F]{6}$/.test(c.color) || typeof c.archived !== 'boolean' || !Array.isArray(c.days) || c.days.length < 1 || c.days.length > 7 || new Set(c.days).size !== c.days.length || c.days.some(x => !Number.isInteger(x) || x < 0 || x > 6) || !/^([01]\d|2[0-3]):[0-5]\d$/.test(c.time)) fail();
      ids.add(c.id);
    }
    for (const s of value.students) {
      if (!s || !idOK(s.id) || studentIds.has(s.id) || !text(s.name, 30, true) || !ids.has(s.classId) || !text(s.memo, 200) || typeof s.active !== 'boolean' || !validDate(s.joinedDate) || !(s.archivedDate === null || validDate(s.archivedDate))) fail();
      studentIds.add(s.id);
    }
    for (const r of value.records) {
      const key = r && `${r.date}|${r.studentId}`;
      if (!r || !studentIds.has(r.studentId) || !ids.has(r.classId) || !validDate(r.date) || !STATUSES.includes(r.status) || !text(r.note, 200) || !text(r.studentName, 30, true) || !text(r.className, 30, true) || !text(r.time, 5) || (r.time !== '' && !/^([01]\d|2[0-3]):[0-5]\d$/.test(r.time)) || typeof r.updatedAt !== 'string' || !Number.isFinite(Date.parse(r.updatedAt)) || keys.has(key)) fail();
      keys.add(key);
    }
    // Copy only known fields: never import prototypes, transient UI state or unrecognized settings.
    return {schema: 1, settings: {academy: value.settings.academy.trim(), teacher: value.settings.teacher.trim()},
      classes: value.classes.map(c => ({id:c.id,name:c.name.trim(),color:c.color,days:[...c.days],time:c.time,archived:c.archived})),
      students: value.students.map(s => ({id:s.id,name:s.name.trim(),classId:s.classId,memo:s.memo,active:s.active,joinedDate:s.joinedDate,archivedDate:s.archivedDate})),
      records: value.records.map(r => ({studentId:r.studentId,classId:r.classId,date:r.date,status:r.status,note:r.note,studentName:r.studentName,className:r.className,time:r.time,updatedAt:r.updatedAt}))};
  }
  function classOf(state, id) { return state.classes.find(c => c.id === id); }
  function recordOf(state, id, date) { return state.records.find(r => r.studentId === id && r.date === date); }
  function roster(state, date, classId = 'all', scheduledOnly = true, query = '') {
    const day = new Date(date + 'T12:00:00').getDay();
    const records = new Map(state.records.filter(r => r.date === date).map(r => [r.studentId, r]));
    const needle = query.trim().toLocaleLowerCase();
    return state.students.map(s => {
      const r = records.get(s.id);
      const c = classOf(state, r ? r.classId : s.classId);
      const eligible = s.joinedDate <= date && (s.active || (s.archivedDate && date < s.archivedDate));
      if (!c || (!r && (!eligible || c.archived || (scheduledOnly && !c.days.includes(day)))) || (classId !== 'all' && c.id !== classId)) return null;
      const name = r ? r.studentName : s.name;
      if (needle && !(name + ' ' + (r ? r.className : c.name)).toLocaleLowerCase().includes(needle)) return null;
      return {student:s, group:c, record:r || null, name, groupName:r ? r.className : c.name};
    }).filter(Boolean).sort((a,b) => a.group.time.localeCompare(b.group.time) || a.name.localeCompare(b.name, 'ko'));
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
    const c = classOf(state, old ? old.classId : s.classId); if (!c) throw new Error('반을 찾을 수 없습니다.');
    if (!old && (date < s.joinedDate || (!s.active && (!s.archivedDate || date >= s.archivedDate)))) throw new Error('이 날짜에는 등록되어 있지 않은 학생입니다.');
    const next = clone(state);
    const ix = next.records.findIndex(r => r.studentId === id && r.date === date);
    if (status === 'unmarked') { if (ix >= 0) next.records.splice(ix, 1); return next; }
    const r = {studentId:id,classId:old ? old.classId : c.id,date,status,note:note === undefined ? (old ? old.note : '') : note,
      studentName:old ? old.studentName : s.name,className:old ? old.className : c.name,
      time:['present','late'].includes(status) ? (old && old.time ? old.time : date === localDate(now) ? `${String(now.getHours()).padStart(2,'0')}:${String(now.getMinutes()).padStart(2,'0')}` : '') : '',updatedAt:now.toISOString()};
    if (ix >= 0) next.records[ix] = r; else next.records.push(r); return next;
  }
  function report(state, month, classId = 'all') {
    const records = state.records.filter(r => r.date.startsWith(month + '-') && (classId === 'all' || r.classId === classId));
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
    return '\uFEFF' + [['날짜','학생','반','출석 상태','체크 시간','메모'],...records.slice().sort((a,b) => a.date.localeCompare(b.date) || a.studentName.localeCompare(b.studentName,'ko')).map(r => [r.date,r.studentName,r.className,LABELS[r.status],r.time,r.note])].map(row => row.map(csvCell).join(',')).join('\r\n');
  }
  function backup(state) { return JSON.stringify({app:'오늘출석',exportedAt:new Date().toISOString(),data:validate(state)},null,2); }
  function parseBackup(raw) {
    if (typeof raw !== 'string' || raw.length > 24 * 1024 * 1024) throw new Error('백업 파일이 너무 큽니다.');
    let value; try { value = JSON.parse(raw.replace(/^\uFEFF/,'')); } catch (_) { throw new Error('읽을 수 없는 백업 파일입니다.'); }
    if (!value || value.app !== '오늘출석' || !value.data) throw new Error('오늘출석에서 만든 JSON 백업 파일을 선택해 주세요.');
    return validate(value.data);
  }
  return {STATUSES,LABELS,clone,localDate,validDate,shiftDate,uid,fresh,validate,classOf,recordOf,roster,count,mark,report,csv,csvCell,backup,parseBackup};
});
