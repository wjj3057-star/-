(function () {
  'use strict';
  const C = window.AttendanceCore;
  const $ = s => document.querySelector(s);
  const e = v => String(v == null ? '' : v).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const days = ['일','월','화','수','목','금','토'];
  const colors = ['#8974C9','#529E99','#D89565','#7297CD','#C27C9A','#919B60'];
  const paths = {
    check:'<path d="m5 12 4 4L19 6"/>', calendar:'<rect x="3" y="5" width="18" height="16" rx="4"/><path d="M7 3v4m10-4v4M3 11h18m-12 4h.01M12 15h.01M16 15h.01"/>',
    users:'<path d="M16 21v-2a4 4 0 0 0-4-4H7a4 4 0 0 0-4 4v2m14-17a4 4 0 0 1 0 8m4 9v-2a4 4 0 0 0-3-3.87"/><circle cx="9.5" cy="6.5" r="4"/>',
    chart:'<path d="M4 20h16M7 16v-5m5 5V4m5 12V8"/>',settings:'<path d="m9 3-1 3-3 1-1 3 2 2-2 3 2 3 3-1 3 4 3-2v-3l4-1 1-4-3-2V6l-3-2-2 2-3-3Z"/><circle cx="12" cy="12" r="3"/>',
    plus:'<path d="M12 5v14M5 12h14"/>',search:'<circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 4.5 4.5"/>',left:'<path d="m14 6-6 6 6 6"/>',right:'<path d="m9 6 6 6-6 6"/>',
    clock:'<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',note:'<path d="M20 10V5a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h6m2-4 6-6 2 2-6 6-3 1 1-3ZM8 7h8M8 11h3"/>',
    close:'<path d="m6 6 12 12M6 18 18 6"/>',book:'<path d="M3 4h6a3 3 0 0 1 3 3v14a4 4 0 0 0-4-3H3V4Zm18 0h-6a3 3 0 0 0-3 3v14a4 4 0 0 1 4-3h5V4Z"/>',
    download:'<path d="M12 3v12m-5-5 5 5 5-5M4 16v4a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-4"/>',upload:'<path d="M12 16V4m-5 5 5-5 5 5M4 16v4a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-4"/>',
    shield:'<path d="m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6l8-3Z"/><path d="m8 12 3 3 5-6"/>',edit:'<path d="m15 4 5 5M4 20l5-1L21 7a2 2 0 0 0-4-4L5 15l-1 5Z"/>',
    archive:'<rect x="3" y="3" width="18" height="5" rx="1"/><path d="M5 8v13h14V8m-10 4h6"/>',info:'<circle cx="12" cy="12" r="9"/><path d="M12 11v6m0-10h.01"/>',home:'<path d="m3 10 9-7 9 7M5 9v12h14V9m-9 12v-7h4v7"/>'
  };
  const icon = name => `<svg class="icon" viewBox="0 0 24 24" aria-hidden="true">${paths[name] || paths.check}</svg>`;
  const native = window.NativeAttendance || null;
  let state, loadError = '', lastFocus = null, undoState = null, toastTimer, importData = null;
  let today = C.localDate();
  const ui = {tab:'attendance',date:today,classId:'all',scheduled:true,query:'',studentQuery:'',studentClass:'all',showArchived:false,month:today.slice(0,7),reportClass:'all'};
  function readState() {
    const raw = native ? JSON.parse(native.readState()) : {ok:true,data:localStorage.getItem('oneul-attendance-v1')};
    if (!raw.ok) throw new Error('저장된 데이터를 읽지 못했습니다. 앱을 다시 열거나 백업 파일을 복원해 주세요.');
    return raw.data ? C.validate(JSON.parse(raw.data)) : C.fresh();
  }
  try { state = readState(); } catch (error) { loadError = error.message; }
  function save(next, message, undo = false) {
    try {
      next = C.validate(next);
      const raw = JSON.stringify(next);
      if (native) {
        const result = JSON.parse(native.saveState(raw));
        if (!result.ok) throw new Error('저장에 실패했습니다. 기기의 저장 공간을 확인해 주세요.');
      } else localStorage.setItem('oneul-attendance-v1', raw);
      undoState = undo ? C.clone(state) : null;
      state = next; loadError = ''; render();
      if (message) toast(message, undo); return true;
    } catch (error) { toast(error.message || '저장하지 못했습니다. 다시 시도해 주세요.'); return false; }
  }
  function saveAttendance(next,message){const previous=state;const ok=save(next,message,true);if(ok&&window.ContactSms)window.ContactSms.afterAttendanceSave(previous,state);return ok;}
  function toast(message, undo = false) {
    clearTimeout(toastTimer);
    $('#toast').innerHTML = `<div class="toast-content"><span>${e(message)}</span>${undo ? '<button class="toast-undo" data-action="undo">되돌리기</button>' : ''}</div>`;
    $('#toast').classList.add('show'); toastTimer = setTimeout(() => $('#toast').classList.remove('show'), undo ? 6500 : 3500);
  }
  const dateLong = date => new Intl.DateTimeFormat('ko-KR',{month:'long',day:'numeric',weekday:'long'}).format(new Date(date+'T12:00:00'));
  const classStyle = c => `--class-color:${c.color};--class-tint:${c.color}18`;
  const groupDays = c => c.days.length === 7 ? '매일' : c.days.slice().sort((a,b)=>(a||7)-(b||7)).map(d=>days[d]).join('·');
  function head(title, subtitle, action = '') { return `<div class="page-head"><div><p class="eyebrow">${e(subtitle)}</p><h1>${e(title)}</h1></div>${action}</div>`; }
  function empty(title, subtitle, button = '', symbol='calendar') { return `<div class="empty"><div class="empty-symbol">${icon(symbol)}</div><h3>${e(title)}</h3><p>${e(subtitle)}</p>${button}</div>`; }
  function nav() { return `<nav class="nav" aria-label="주 메뉴">${[['attendance','calendar','출석 체크'],['students','users','학생 관리'],['reports','chart','출석 기록'],['settings','settings','설정']].map(([id,im,label])=>`<button data-action="tab" data-tab="${id}" class="${ui.tab===id?'active':''}" aria-current="${ui.tab===id?'page':'false'}">${icon(im)}<span>${label}</span></button>`).join('')}</nav>`; }
  function render() {
    if (loadError) {
      $('#app').innerHTML = `<div class="loading-error"><div class="empty-symbol">${icon('shield')}</div><h2>데이터를 확인해 주세요</h2><p>${e(loadError)}</p><button class="secondary" data-action="reload">다시 읽기</button><br><button class="primary" data-action="import">백업 파일 복원</button></div>`; return;
    }
    $('#app').innerHTML = `<div class="app-shell"><header class="topbar"><div class="brand"><span class="brand-mark">${icon('calendar')}</span>오늘출석</div><div class="local-badge"><i></i>이 기기에 자동 저장</div></header>${({attendance:attendance,students:students,reports:reports,settings:settings})[ui.tab]()}</div>${nav()}`;
  }
  function chips(selected, field, includeArchived = false) {
    const groups = state.classes.filter(c => includeArchived || !c.archived);
    return `<div class="filter-scroll" aria-label="반 선택"><button class="chip ${selected==='all'?'active':''}" data-action="filter" data-field="${field}" data-id="all">전체 반</button>${groups.map(c=>`<button class="chip ${selected===c.id?'active':''}" data-action="filter" data-field="${field}" data-id="${e(c.id)}"><i style="--class-color:${c.color}"></i>${e(c.name)}${c.archived?' · 보관':''}</button>`).join('')}</div>`;
  }
  function dateControls() {
    const start = C.shiftDate(ui.date, -((new Date(ui.date+'T12:00:00').getDay()+6)%7));
    return `<div class="date-nav"><button class="icon-button" data-action="date-shift" data-amount="-1" aria-label="이전 날짜">${icon('left')}</button><label class="date-field">${icon('calendar')}<input type="date" id="attendance-date" aria-label="출석 날짜" value="${ui.date}" min="2000-01-01" max="2100-12-31"></label><button class="icon-button" data-action="date-shift" data-amount="1" aria-label="다음 날짜">${icon('right')}</button><button class="date-today" data-action="today">오늘</button></div><div class="week-strip">${Array.from({length:7},(_,i)=>{const d=C.shiftDate(start,i);return `<button class="day-pill ${d===ui.date?'active':''} ${d===today?'today':''}" data-action="date-select" data-date="${d}" aria-label="${e(dateLong(d))}" aria-pressed="${d===ui.date}"><span>${days[new Date(d+'T12:00:00').getDay()]}</span><b>${Number(d.slice(8))}</b></button>`;}).join('')}</div>`;
  }
  function attendance() {
    const rows = C.roster(state,ui.date,ui.classId,ui.scheduled);
    const t = C.count(rows), checked=t.total-t.unmarked, percent=t.total?Math.round(checked/t.total*100):0;
    const greeting = state.settings.teacher ? state.settings.teacher+' 선생님' : state.settings.academy;
    const hero = `<section class="hero" aria-label="출석 요약"><div class="hero-top"><div><p class="hero-label">${ui.date===today?'오늘의':'선택한 날짜의'} 출석 현황</p><div class="hero-number">${t.present+t.late}<span>/ ${t.total}명 등원</span></div><p class="hero-caption">${e(dateLong(ui.date))}</p></div><div class="ring-wrap"><svg viewBox="0 0 92 92" aria-hidden="true"><circle cx="46" cy="46" r="37" fill="none" stroke="#dcd5ef" stroke-width="7"/><circle cx="46" cy="46" r="37" fill="none" stroke="#9480d3" stroke-width="7" stroke-linecap="round" stroke-dasharray="${percent*2.3248} 232.48"/></svg><b>${percent}<span>%</span></b></div></div><div class="hero-stats">${[['present','green'],['late','amber'],['absent','red'],['excused',''],['unmarked','']].map(([s,cl])=>`<div class="hero-stat ${cl}"><b>${t[s]}</b><span>${C.LABELS[s]}</span></div>`).join('')}</div></section><p class="hint">체크 완료 ${checked}명 / ${t.total}명 · 공결은 출석률에서 제외돼요.</p>`;
    return head('출석 체크',greeting,`<div class="avatar" aria-hidden="true">${e((state.settings.teacher||'T').slice(0,1))}</div>`)+`<div class="attendance-layout"><div class="attendance-sidebar">${dateControls()}${hero}</div><section class="attendance-main"><div class="section-title"><h2>학생 출석부 <span class="count-tag">${rows.length}</span></h2><button class="text-button" data-action="student-add">${icon('plus')} 학생 추가</button></div>${chips(ui.classId,'classId')}<label class="search-box">${icon('search')}<input id="attendance-search" placeholder="학생 이름으로 검색" value="${e(ui.query)}" aria-label="출석부 학생 검색"></label><div class="list-tools"><label class="toggle"><input id="scheduled-toggle" type="checkbox" ${ui.scheduled?'checked':''}>수업 있는 학생만</label><button class="text-button" data-action="bulk-present" ${ui.date>today?'disabled':''}>미확인 모두 출석 ${icon('check')}</button></div>${ui.date>today?'<div class="notice">미래 날짜의 명단이에요. 출석은 해당 날짜부터 기록할 수 있어요.</div>':''}<div id="attendance-list">${attendanceList()}</div><div class="saving-footer">${icon('shield')}출석 기록은 자동으로 저장돼요</div></section></div>`;
  }
  function attendanceList() {
    if (!state.students.length) return empty('첫 학생을 등록해 주세요','반과 학생을 한 번 등록하면\n매일 간편하게 출석을 체크할 수 있어요.',`<button class="primary" data-action="student-add">${icon('plus')} 학생 등록하기</button>`,'users');
    const rows = C.roster(state,ui.date,ui.classId,ui.scheduled,ui.query);
    if (!rows.length) return empty(ui.query?'검색 결과가 없어요':'이 날짜의 학생이 없어요',ui.query?'학생 이름이나 반 이름을 다시 확인해 주세요.':'반의 수업 요일과 학생의 등록일을 확인하거나\n‘수업 있는 학생만’을 꺼 보세요.');
    return `<div class="student-list">${rows.map(row=>{
      const {student:s,group:c,record:r}=row, st=r?r.status:'unmarked';
      return `<article class="student-card" data-student="${e(s.id)}"><div class="student-top"><div class="student-avatar" style="${classStyle(c)}">${e(row.name.slice(0,1))}</div><button class="student-info" data-action="student-detail" data-id="${e(s.id)}" aria-label="${e(row.name)} 기록 보기"><h3>${e(row.name)}</h3><p><i class="color-dot" style="--class-color:${c.color}"></i><span>${e(row.groupName)}</span><span>· ${e(c.time)}</span></p></button>${r&&r.time?`<span class="student-meta">${icon('clock')}${e(r.time)}</span>`:''}<button class="note-button ${r&&r.note?'has-note':''}" data-action="record-note" data-id="${e(s.id)}" aria-label="${e(row.name)} 출석 메모">${icon('note')}</button></div><div class="status-row" role="group" aria-label="${e(row.name)} 출석 상태">${C.STATUSES.map(status=>`<button class="status-button ${status} ${st===status?'selected':''}" data-action="mark" data-id="${e(s.id)}" data-status="${status}" aria-pressed="${st===status}" ${ui.date>today?'disabled':''}>${icon('check')}${C.LABELS[status]}</button>`).join('')}</div>${r&&r.note?`<p class="record-note">${e(r.note)}</p>`:''}</article>`;
    }).join('')}</div>`;
  }
  function students() {
    const active = state.students.filter(s=>s.active).length;
    return head('학생 관리',`등록된 학생 ${active}명`, `<button class="primary small-button" data-action="student-add">${icon('plus')} 학생 등록</button>`)+chips(ui.studentClass,'studentClass',ui.showArchived)+`<label class="search-box">${icon('search')}<input id="student-search" placeholder="이름 또는 반 이름 검색" value="${e(ui.studentQuery)}" aria-label="학생 관리 검색"></label><div class="list-tools"><span class="muted"><small>학생을 눌러 정보와 기록을 확인하세요.</small></span><label class="toggle"><input id="archived-toggle" type="checkbox" ${ui.showArchived?'checked':''}>보관 포함</label></div>${window.ContactSms?window.ContactSms.studentTools():''}<div id="student-list">${studentList()}</div><p class="hint">퇴원한 학생은 보관할 수 있어요. 이전 출석 기록은 그대로 유지돼요.</p>`;
  }
  function studentList() {
    const query=ui.studentQuery.trim().toLowerCase();
    const list=state.students.filter(s=>(s.active||ui.showArchived)&&(ui.studentClass==='all'||s.classId===ui.studentClass)&&(!query||(s.name+' '+C.classOf(state,s.classId).name).toLowerCase().includes(query))).sort((a,b)=>Number(b.active)-Number(a.active)||a.name.localeCompare(b.name,'ko'));
    if (!list.length) return empty('등록된 학생이 없어요','학생을 등록하거나 검색 조건을 바꿔 주세요.',`<button class="primary" data-action="student-add">${icon('plus')} 학생 등록</button>`,'users');
    return `<div class="student-list students-grid">${list.map(s=>{const c=C.classOf(state,s.classId);return `<button class="student-card row ${s.active?'':'archived'}" data-action="student-detail" data-id="${e(s.id)}" style="text-align:left;min-height:88px"><span class="student-avatar" style="${classStyle(c)}">${e(s.name.slice(0,1))}</span><span class="student-info"><h3>${e(s.name)} ${s.active?'':'<span class="label-pill">보관</span>'}</h3><p>${e(c.name)} · ${e(groupDays(c))}</p></span>${icon('right')}</button>`;}).join('')}</div>`;
  }
  function monthControls() { return `<div class="date-nav"><button class="icon-button" data-action="month-shift" data-amount="-1" aria-label="이전 달">${icon('left')}</button><label class="date-field">${icon('calendar')}<input type="month" id="report-month" aria-label="기록 조회 월" value="${ui.month}" min="2000-01" max="2100-12"></label><button class="icon-button" data-action="month-shift" data-amount="1" aria-label="다음 달">${icon('right')}</button><button class="date-today" data-action="this-month">이번 달</button></div>`; }
  function reports() {
    const rep=C.report(state,ui.month,ui.reportClass),t=rep.totals;
    const [year,month]=ui.month.split('-').map(Number), start=new Date(year,month-1,1).getDay(),num=new Date(year,month,0).getDate();
    const calendar=`<div class="calendar"><div class="calendar-head">${days.map(d=>`<div>${d}</div>`).join('')}</div><div class="calendar-grid">${'<span></span>'.repeat(start)}${Array.from({length:num},(_,i)=>{const d=`${ui.month}-${String(i+1).padStart(2,'0')}`,rec=rep.records.filter(r=>r.date===d),sts=new Set(rec.map(r=>r.status));return `<button class="calendar-day ${rec.length?'has-record':''} ${d===today?'is-today':''} ${d>today?'future':''}" data-action="calendar-date" data-date="${d}" aria-label="${e(dateLong(d))}, ${rec.length}건"><span>${i+1}</span><span class="day-dots">${C.STATUSES.filter(s=>sts.has(s)).map(s=>`<i class="${s}"></i>`).join('')}</span></button>`;}).join('')}</div><div class="calendar-legend">${[['present','#34a280'],['late','#d6a245'],['absent','#d77585'],['excused','#9f80cf']].map(([s,color])=>`<span><i style="background:${color}"></i>${C.LABELS[s]}</span>`).join('')}</div></div>`;
    const byStudent=new Map();rep.records.forEach(r=>{if(!byStudent.has(r.studentId))byStudent.set(r.studentId,{id:r.studentId,name:r.studentName,counts:{present:0,late:0,absent:0,excused:0}});byStudent.get(r.studentId).counts[r.status]++;});
    const studentsHTML=byStudent.size?`<div class="white-card">${[...byStudent.values()].sort((a,b)=>a.name.localeCompare(b.name,'ko')).map(s=>`<button class="history-row" data-action="student-detail" data-id="${e(s.id)}"><div><strong>${e(s.name)}</strong></div><div class="history-counts">${C.STATUSES.map(st=>`<span class="${st}" aria-label="${C.LABELS[st]} ${s.counts[st]}회">${s.counts[st]}</span>`).join('')}${icon('right')}</div></button>`).join('')}</div>`:empty('아직 출석 기록이 없어요','선택한 달에 출석을 체크하면\n여기에서 학생별 기록을 볼 수 있어요.','', 'chart');
    return head('출석 기록','하루하루 쌓이는 우리 반의 기록',`<button class="icon-button" data-action="export-month" aria-label="월간 CSV 내보내기">${icon('download')}</button>`)+monthControls()+chips(ui.reportClass,'reportClass',true)+`<div class="report-layout section-gap"><div><section class="report-hero"><p class="eyebrow">${month}월 출석률</p><div class="report-big">${rep.rate===null?'—':rep.rate}<span>${rep.rate===null?'':'%'}</span></div><p class="report-copy">(출석 + 지각) ÷ (출석 + 지각 + 결석)<br>공결과 미확인은 제외해요. 횟수는 학생별 체크 건수예요.</p><div class="report-counts">${C.STATUSES.map(s=>`<div><b>${t[s]}<small>회</small></b>${C.LABELS[s]}</div>`).join('')}</div></section>${calendar}<p class="hint">날짜를 누르면 그날의 출석부로 이동해요.</p></div><div><div class="section-title"><h2>학생별 기록</h2><span class="muted"><small>출석 · 지각 · 결석 · 공결</small></span></div>${studentsHTML}<button class="secondary full" data-action="export-month">${icon('download')} 이 달 기록 CSV 저장</button></div></div>`;
  }
  function settings() {
    const groups=state.classes.filter(c=>!c.archived);
    const settingsRow=(action,im,label,sub)=>`<button class="settings-row" data-action="${action}"><span class="settings-icon">${icon(im)}</span><span class="settings-label"><b>${e(label)}</b><small>${e(sub)}</small></span>${icon('right')}</button>`;
    return head('설정','우리 학원에 맞게 관리해 보세요')+`<div class="settings-layout"><section><div class="settings-title">학원 정보</div><div class="settings-card">${settingsRow('academy-edit','home',state.settings.academy,state.settings.teacher?state.settings.teacher+' 선생님':'학원 이름과 선생님 이름 설정')}</div><div class="section-title"><h2>반 관리 <span class="count-tag">${groups.length}</span></h2><button class="text-button" data-action="class-add">${icon('plus')} 반 추가</button></div>${groups.length?groups.map(c=>`<button class="class-row" data-action="class-edit" data-id="${e(c.id)}"><span class="class-icon" style="${classStyle(c)}">${icon('book')}</span><span class="class-info"><strong>${e(c.name)}</strong><small>${e(groupDays(c))} · ${e(c.time)} · ${state.students.filter(s=>s.active&&s.classId===c.id).length}명</small></span>${icon('right')}</button>`).join(''):empty('첫 번째 반을 만들어 주세요','반 이름, 수업 요일과 시작 시간을 설정하세요.',`<button class="secondary" data-action="class-add">반 만들기</button>`,'book')}${state.classes.some(c=>c.archived)?`<button class="text-button" data-action="archived-classes">보관된 반 보기</button>`:''}</section><section>${window.ContactSms?window.ContactSms.settingsHTML():''}<div class="settings-title">데이터 관리</div><div class="settings-card">${settingsRow('backup','download','전체 데이터 백업','학생·반·출석 기록을 JSON 파일로 저장')}${settingsRow('import','upload','백업 파일 복원','저장해 둔 백업으로 현재 데이터를 교체')}${settingsRow('export-all','chart','전체 출석 기록 내보내기','엑셀에서 열 수 있는 CSV 파일로 저장')}</div><div class="white-card"><div class="row" style="color:#8873ad;margin-bottom:11px">${icon('shield')}<h3>이 기기에 안전하게 보관</h3></div><p class="modal-copy">학생 정보와 출석 기록은 이 기기에 저장돼요. 연락처 연동은 읽기 권한을, 학부모 문자는 SMS 권한과 문자 발송이 가능한 SIM을 사용해요. 다른 기기와 자동 동기화되지 않아요.</p><p class="hint">기기를 바꾸거나 앱을 삭제하기 전에 전체 데이터를 백업해 주세요. 백업 파일에는 학생 이름과 기록이 포함돼요.</p></div><p class="hint" style="text-align:center">오늘출석 1.1.0 · 매일의 시작을 가볍게</p></section></div>`;
  }
  function modal(title, body, onSubmit) {
    lastFocus=document.activeElement;
    $('#modal-root').innerHTML=`<div class="modal-backdrop"><section class="modal" role="dialog" aria-modal="true" aria-labelledby="modal-title" tabindex="-1"><div class="modal-header"><h2 id="modal-title">${e(title)}</h2><button class="icon-button" data-action="close" aria-label="닫기">${icon('close')}</button></div>${body}</section></div>`;
    document.body.style.overflow='hidden';
    const form=$('#modal-root form');if(form&&onSubmit)form.addEventListener('submit',ev=>{ev.preventDefault();onSubmit(new FormData(form),form);});
    $('.modal').focus();
  }
  function closeModal(){ if(window.ContactSms)window.ContactSms.onClose();$('#modal-root').innerHTML='';document.body.style.overflow='';if(lastFocus&&lastFocus.isConnected)lastFocus.focus();importData=null; }
  function formError(message){const target=$('#form-error');if(target){target.textContent=message;target.classList.add('visible');}else toast(message);}
  const errorSlot='<div id="form-error" class="form-error" role="alert"></div>';
  function confirmDialog(title,message,label,callback,danger=false){
    modal(title,`<p class="modal-copy">${e(message)}</p><div class="modal-actions"><button class="secondary" data-action="close">취소</button><button id="confirm-action" class="${danger?'danger-button':'primary'}">${e(label)}</button></div>`);
    $('#confirm-action').addEventListener('click',()=>{closeModal();callback();});
  }
  function classForm(id, after) {
    const old=state.classes.find(c=>c.id===id),c=old||{name:'',days:[1,2,3,4,5],time:'16:00',color:colors[0]};
    modal(old?'반 정보 수정':'새 반 만들기',`<form><label class="field"><span>반 이름</span><input name="name" required maxlength="30" value="${e(c.name)}" placeholder="예: 중등 수학 A반" autocomplete="off"></label><div class="field"><span>수업 요일</span><div class="weekday-select">${days.map((d,i)=>`<label><input type="checkbox" name="days" value="${i}" ${c.days.includes(i)?'checked':''}><span>${d}</span></label>`).join('')}</div></div><label class="field"><span>수업 시작 시간</span><input type="time" name="time" required value="${c.time}"></label><div class="field"><span>반 색상</span><div class="color-select">${colors.map(color=>`<label style="--class-color:${color}"><input type="radio" name="color" value="${color}" ${c.color===color?'checked':''} aria-label="${color}"><span></span></label>`).join('')}</div></div>${old?'<p class="hint">요일 변경은 미기록 날짜의 명단에도 적용돼요. 이미 저장한 출석 기록의 반 이름과 상태는 유지돼요.</p>':''}${errorSlot}<div class="modal-actions"><button type="button" class="secondary" data-action="close">취소</button><button type="submit" class="primary">${old?'변경 저장':'반 만들기'}</button></div>${old?`<button type="button" class="text-button text-danger full" data-action="class-archive" data-id="${e(id)}">${icon('archive')} 이 반 보관하기</button>`:''}</form>`,fd=>{
      const name=String(fd.get('name')||'').trim(),selected=fd.getAll('days').map(Number),time=String(fd.get('time')||''),color=fd.get('color')||c.color;
      if(!name)return formError('반 이름을 입력해 주세요.');if(!selected.length)return formError('수업 요일을 하나 이상 선택해 주세요.');
      if(state.classes.some(x=>!x.archived&&x.id!==id&&x.name===name))return formError('같은 이름의 반이 이미 있어요.');
      const next=C.clone(state),group={id:old?id:C.uid(),name,days:selected,time,color,archived:false};
      if(old)next.classes[next.classes.findIndex(x=>x.id===id)]=group;else next.classes.push(group);
      if(save(next,old?'반 정보를 수정했어요.':'새 반을 만들었어요.')){closeModal();if(after)after(group.id);}
    });
  }
  function studentForm(id, selectedClass) {
    const groups=state.classes.filter(c=>!c.archived);
    if(!groups.length){classForm(null,group=>studentForm(id,group));return;}
    const old=state.students.find(s=>s.id===id),s=old||{name:'',classId:selectedClass||((ui.studentClass!=='all'&&groups.some(c=>c.id===ui.studentClass))?ui.studentClass:groups[0].id),memo:'',joinedDate:today};
    modal(old?'학생 정보 수정':'새 학생 등록',`<form><label class="field"><span>학생 이름</span><input name="name" required maxlength="30" value="${e(s.name)}" placeholder="학생 이름을 입력하세요" autocomplete="off"></label><label class="field"><span>소속 반</span><select name="classId" required>${groups.map(c=>`<option value="${e(c.id)}" ${s.classId===c.id?'selected':''}>${e(c.name)}</option>`).join('')}</select></label><label class="field"><span>등록일</span><input type="date" name="joinedDate" required min="2000-01-01" max="${today}" value="${s.joinedDate}"><small>등록일 이후의 출석부에 표시돼요. 지난 출석도 입력할 수 있어요.</small></label><label class="field"><span>학생 메모 <small style="display:inline">선택</small></span><textarea name="memo" maxlength="200" placeholder="수업 관련 참고사항을 적어 주세요">${e(s.memo)}</textarea></label>${window.ContactSms?window.ContactSms.fields(s):''}${old?'<p class="hint">이름이나 반을 바꿔도 이전에 저장한 출석 기록은 유지돼요.</p>':''}${errorSlot}<div class="modal-actions"><button type="button" class="secondary" data-action="close">취소</button><button class="primary" type="submit">${old?'변경 저장':'학생 등록'}</button></div></form>`,fd=>{
      const name=String(fd.get('name')||'').trim(),classId=String(fd.get('classId')||''),joinedDate=String(fd.get('joinedDate')||''),memo=String(fd.get('memo')||'').trim();
      if(!name)return formError('학생 이름을 입력해 주세요.');if(!C.validDate(joinedDate)||joinedDate>today)return formError('등록일을 확인해 주세요.');
      if(state.students.some(x=>x.active&&x.id!==id&&x.name===name&&x.classId===classId))return formError('이 반에 같은 이름의 학생이 있어요. 이름 뒤에 구분 표시를 넣어 주세요.');
      let contact;try{contact=window.ContactSms?window.ContactSms.readFields($('#modal-root form')):{phone:'',contactId:'',guardians:[]};}catch(error){return formError(error.message);}
      const next=C.clone(state);if(old)Object.assign(next.students.find(x=>x.id===id),{name,classId,memo,joinedDate,...contact});else next.students.push({id:C.uid(),name,classId,memo,joinedDate,active:true,archivedDate:null,...contact});
      if(save(next,old?'학생 정보를 수정했어요.':'학생을 등록했어요.'))closeModal();
    });
  }
  function studentDetail(id) {
    const s=state.students.find(x=>x.id===id);if(!s)return;
    const c=C.classOf(state,s.classId),month=ui.tab==='reports'?ui.month:ui.date.slice(0,7);
    const records=state.records.filter(r=>r.studentId===id&&r.date.startsWith(month+'-')&&(ui.tab!=='reports'||ui.reportClass==='all'||r.classId===ui.reportClass)).sort((a,b)=>b.date.localeCompare(a.date)),t=C.count(records.map(r=>({record:r})));
    modal(s.name,`<p class="modal-subtitle">${e(c.name)} · ${e(s.joinedDate)} 등록${s.active?'':' · 보관 중'}</p>${s.memo?`<div class="notice">${e(s.memo)}</div>`:''}${window.ContactSms?window.ContactSms.details(s):''}<div class="detail-stats">${C.STATUSES.map(st=>`<div><b>${t[st]}</b>${C.LABELS[st]}</div>`).join('')}</div><p class="record-summary">${e(month.replace('-','년 '))}월 기록 ${records.length}건</p>${records.length?records.map(r=>`<div class="log-row"><div class="row between"><span class="log-date">${e(dateLong(r.date))}<br><small>${e(r.className)}${r.time?' · '+e(r.time):''}</small></span><button class="status-label ${r.status}" data-action="detail-date" data-date="${r.date}">${C.LABELS[r.status]} ${icon('right')}</button></div>${r.note?`<p class="log-note">${e(r.note)}</p>`:''}</div>`).join(''):'<p class="hint">이 달에는 저장된 출석 기록이 없어요.</p>'}<div class="modal-actions"><button class="secondary" data-action="student-edit" data-id="${e(id)}">${icon('edit')} 정보 수정</button><button class="${s.active?'danger-button':'primary'}" data-action="student-archive" data-id="${e(id)}">${s.active?'학생 보관':'보관 해제'}</button></div>`);
  }
  function recordNote(id) {
    if(ui.date>today)return toast('미래 날짜에는 기록할 수 없어요.');
    const s=state.students.find(x=>x.id===id),r=C.recordOf(state,id,ui.date);if(!s)return;
    modal('출석 메모',`<p class="modal-subtitle">${e(r?r.studentName:s.name)} · ${e(dateLong(ui.date))}</p><form><label class="field"><span>출석 상태</span><select name="status">${C.STATUSES.map(st=>`<option value="${st}" ${(r?r.status:'present')===st?'selected':''}>${C.LABELS[st]}</option>`).join('')}</select></label><label class="field"><span>메모</span><textarea name="note" maxlength="200" placeholder="예: 병원 진료, 10분 늦게 등원">${e(r?r.note:'')}</textarea></label>${errorSlot}<div class="modal-actions"><button type="button" class="secondary" data-action="close">취소</button><button type="submit" class="primary">메모 저장</button></div>${r?`<button type="button" class="text-button text-danger full" data-action="clear-record" data-id="${e(id)}">이날 기록을 미확인으로 되돌리기</button>`:''}</form>`,fd=>{
      try { if(saveAttendance(C.mark(state,id,ui.date,fd.get('status'),String(fd.get('note')||'').trim()),'출석 메모를 저장했어요.'))closeModal(); } catch(error){formError(error.message);}
    });
  }
  function academyForm(){modal('학원 정보',`<form><label class="field"><span>학원 이름</span><input name="academy" maxlength="40" required value="${e(state.settings.academy)}"></label><label class="field"><span>선생님 이름 <small style="display:inline">선택</small></span><input name="teacher" maxlength="30" value="${e(state.settings.teacher)}" placeholder="선생님 이름"></label>${errorSlot}<div class="modal-actions"><button type="button" class="secondary" data-action="close">취소</button><button type="submit" class="primary">저장</button></div></form>`,fd=>{const academy=String(fd.get('academy')||'').trim();if(!academy)return formError('학원 이름을 입력해 주세요.');const next=C.clone(state);next.settings={...next.settings,academy,teacher:String(fd.get('teacher')||'').trim()};if(save(next,'학원 정보를 저장했어요.'))closeModal();});}
  function exportFile(name,type,content){
    try {
      if(native){native.exportFile(name,type,content);return;}
      const a=document.createElement('a'),url=URL.createObjectURL(new Blob([content],{type}));a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);toast('파일을 내려받았어요.');
    }catch(error){toast('파일을 저장하지 못했어요. 다시 시도해 주세요.');}
  }
  function requestImport(){if(native)native.importFile();else{$('#import-file').value='';$('#import-file').click();}}
  function handleImport(raw){
    try {
      const next=C.parseBackup(raw);next.settings.sms.enabled=false;importData=next;
      modal('백업을 복원할까요?',`<p class="modal-copy">${e(next.settings.academy)}\n학생 ${next.students.length}명 · 반 ${next.classes.length}개\n출석 기록 ${next.records.length}건\n\n현재 데이터를 이 백업으로 교체해요. 복원 전 현재 데이터의 백업을 권장해요. 문자 자동 발송은 꺼진 상태로 복원돼요.</p><label class="check-row"><input id="restore-consent" type="checkbox">현재 데이터가 교체되는 것을 확인했어요.</label><div class="modal-actions"><button class="secondary" data-action="close">취소</button><button class="primary" id="restore-confirm" disabled>복원하기</button></div>`);
      $('#restore-consent').addEventListener('change',ev=>$('#restore-confirm').disabled=!ev.target.checked);
      $('#restore-confirm').addEventListener('click',()=>{if(!$('#restore-consent').checked)return;const restored=importData;if(save(restored,'백업 데이터를 복원했어요.')){closeModal();ui.classId=ui.studentClass=ui.reportClass='all';render();}});
    }catch(error){toast(error.message);}
  }
  function markStudent(id,status){try {const old=C.recordOf(state,id,ui.date);if(saveAttendance(C.mark(state,id,ui.date,status=== (old&&old.status)?'unmarked':status),status===(old&&old.status)?'미확인으로 되돌렸어요.':`${C.LABELS[status]}으로 저장했어요.`)){} }catch(error){toast(error.message);}}
  function setDate(date){if(!C.validDate(date))return;ui.date=date;ui.query='';render();}
  document.addEventListener('click',ev=>{
    const b=ev.target.closest('[data-action]');if(!b||b.disabled)return;
    const a=b.dataset.action,id=b.dataset.id;
    if(a==='tab'){ui.tab=b.dataset.tab;render();window.scrollTo(0,0);}
    else if(a==='filter'){ui[b.dataset.field]=id;render();}
    else if(a==='close')closeModal();
    else if(a==='student-add')studentForm();
    else if(a==='student-edit')studentForm(id);
    else if(a==='student-detail')studentDetail(id);
    else if(a==='class-add')classForm();
    else if(a==='class-edit')classForm(id);
    else if(a==='academy-edit')academyForm();
    else if(a==='date-shift')setDate(C.shiftDate(ui.date,Number(b.dataset.amount)));
    else if(a==='today')setDate(today);
    else if(a==='date-select')setDate(b.dataset.date);
    else if(a==='mark')markStudent(id,b.dataset.status);
    else if(a==='record-note')recordNote(id);
    else if(a==='clear-record')confirmDialog('출석 기록을 지울까요?','이 날짜의 출석 상태와 메모가 지워지고 미확인으로 돌아가요.','미확인으로',()=>{try{save(C.mark(state,id,ui.date,'unmarked'),'기록을 미확인으로 되돌렸어요.',true);}catch(error){toast(error.message);}},true);
    else if(a==='undo'&&undoState){const previous=undoState;save(previous,'이전 상태로 되돌렸어요.');}
    else if(a==='bulk-present'){
      const rows=C.roster(state,ui.date,ui.classId,ui.scheduled,ui.query).filter(row=>!row.record);if(!rows.length)return toast('현재 목록의 출석을 모두 확인했어요.');
      const date=ui.date;
      confirmDialog('한 번에 출석 처리할까요?',`${dateLong(date)}\n현재 검색·반 조건에 해당하는 미확인 학생 ${rows.length}명만 출석 처리해요.\n이미 체크한 학생의 상태는 유지돼요.${state.settings.sms.enabled?'\n문자 수신을 켠 학부모에게 출석 문자도 발송됩니다.':''}`,'출석 처리',()=>{try{let next=state;rows.forEach(row=>{next=C.mark(next,row.student.id,date,'present');});saveAttendance(next,`${rows.length}명을 출석 처리했어요.`);}catch(error){toast(error.message);}});
    }
    else if(a==='student-archive'){
      const s=state.students.find(x=>x.id===id);if(!s)return;
      if(!s.active&&C.classOf(state,s.classId).archived)return toast('먼저 소속 반의 보관을 해제하거나 학생의 반을 바꿔 주세요.');
      confirmDialog(s.active?'학생을 보관할까요?':'보관을 해제할까요?',s.active?`${s.name} 학생을 오늘부터 출석 명단에서 제외해요.\n이미 저장한 출석 기록은 계속 조회할 수 있어요.`:`${s.name} 학생을 다시 출석 명단에 표시해요.`,s.active?'보관하기':'보관 해제',()=>{const next=C.clone(state),target=next.students.find(x=>x.id===id);target.active=!target.active;target.archivedDate=target.active?null:today;save(next,target.active?'학생 보관을 해제했어요.':'학생을 보관했어요.',true);},s.active);
    }
    else if(a==='class-archive'){
      if(state.students.some(s=>s.active&&s.classId===id))return formError('소속 학생을 다른 반으로 옮기거나 보관한 뒤 반을 보관해 주세요.');
      confirmDialog('이 반을 보관할까요?','저장된 출석 기록은 유지돼요. 보관된 반은 새 학생의 소속 반에서 제외돼요.','반 보관',()=>{const next=C.clone(state);next.classes.find(c=>c.id===id).archived=true;ui.classId=ui.studentClass='all';save(next,'반을 보관했어요.');},true);
    }
    else if(a==='archived-classes'){modal('보관된 반',state.classes.filter(c=>c.archived).map(c=>`<button class="settings-row" data-action="class-restore" data-id="${e(c.id)}"><span class="settings-label"><b>${e(c.name)}</b><small>눌러서 보관 해제</small></span>${icon('right')}</button>`).join(''));}
    else if(a==='class-restore'){const next=C.clone(state),group=next.classes.find(c=>c.id===id);if(next.classes.some(c=>!c.archived&&c.name===group.name))return toast('같은 이름의 반이 있어요. 기존 반 이름을 먼저 수정해 주세요.');group.archived=false;if(save(next,'반 보관을 해제했어요.'))closeModal();}
    else if(a==='calendar-date'||a==='detail-date'){closeModal();ui.classId=ui.reportClass;ui.tab='attendance';setDate(b.dataset.date);window.scrollTo(0,0);}
    else if(a==='month-shift'){const d=new Date(ui.month+'-01T12:00:00');d.setMonth(d.getMonth()+Number(b.dataset.amount));const date=C.localDate(d);if(C.validDate(date)){ui.month=date.slice(0,7);render();}}
    else if(a==='this-month'){ui.month=today.slice(0,7);render();}
    else if(a==='backup')exportFile(`오늘출석_백업_${today}.json`,'application/json',C.backup(state));
    else if(a==='export-all')exportFile(`오늘출석_전체기록_${today}.csv`,'text/csv',C.csv(state.records));
    else if(a==='export-month')exportFile(`오늘출석_${ui.month}_${ui.reportClass==='all'?'전체':C.classOf(state,ui.reportClass).name}.csv`,'text/csv',C.csv(C.report(state,ui.month,ui.reportClass).records));
    else if(a==='import')requestImport();
    else if(a==='reload'){try{state=readState();loadError='';}catch(error){loadError=error.message;}render();}
  });
  document.addEventListener('input',ev=>{
    if(ev.target.id==='attendance-search'){ui.query=ev.target.value;$('#attendance-list').innerHTML=attendanceList();}
    if(ev.target.id==='student-search'){ui.studentQuery=ev.target.value;$('#student-list').innerHTML=studentList();}
  });
  document.addEventListener('change',ev=>{
    if(ev.target.id==='attendance-date')setDate(ev.target.value);
    else if(ev.target.id==='scheduled-toggle'){ui.scheduled=ev.target.checked;render();}
    else if(ev.target.id==='archived-toggle'){ui.showArchived=ev.target.checked;if(!ui.showArchived)ui.studentClass='all';render();}
    else if(ev.target.id==='report-month'){if(C.validDate(ev.target.value+'-01')){ui.month=ev.target.value;render();}}
    else if(ev.target.id==='import-file'&&ev.target.files[0]){const file=ev.target.files[0];if(file.size>24*1024*1024)return toast('백업 파일은 24MB 이하만 복원할 수 있어요.');file.text().then(handleImport).catch(()=>toast('파일을 읽지 못했어요.'));}
  });
  document.addEventListener('keydown',ev=>{
    const modalEl=$('.modal');if(!modalEl)return;
    if(ev.key==='Escape'){ev.preventDefault();if(window.ContactSms&&window.ContactSms.handleBack())return;closeModal();}
    if(ev.key==='Tab'){
      const items=[...modalEl.querySelectorAll('button,input,select,textarea,[tabindex="0"]')].filter(x=>!x.disabled&&x.offsetParent!==null),first=items[0],last=items[items.length-1];
      if(ev.shiftKey&&(document.activeElement===first||document.activeElement===modalEl)){ev.preventDefault();last.focus();}
      else if(!ev.shiftKey&&document.activeElement===last){ev.preventDefault();first.focus();}
    }
  });
  function onResume(){const now=C.localDate();if(now!==today){if(ui.date===today)ui.date=now;if(ui.month===today.slice(0,7))ui.month=now.slice(0,7);today=now;if(!$('.modal'))render();}}
  window.AttendanceApp={getState:()=>state,save,render,modal,closeModal,formError,toast,smsToast:message=>toast(message,!!undoState),confirmDialog,icon,escape:e,classForm,studentForm,onImport:handleImport,onNativeResult:message=>toast(message),onResume,handleBack:()=>{if(window.ContactSms&&window.ContactSms.handleBack())return true;if($('.modal')){closeModal();return true;}if(ui.tab!=='attendance'){ui.tab='attendance';render();return true;}return false;}};
  document.addEventListener('visibilitychange',()=>{if(!document.hidden)onResume();});setInterval(onResume,60000);
  render();
})();
