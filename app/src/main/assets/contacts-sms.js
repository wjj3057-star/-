(function(){
  'use strict';
  const A=window.AttendanceApp,C=window.AttendanceCore,N=window.NativeAttendance||null,e=A.escape,$=s=>document.querySelector(s);
  const relations={mother:'어머니',father:'아버지',guardian:'보호자',student:'학생'};
  let contactMode=null,contacts=[],candidates=[],scanToken='',guardianSequence=0,contactAwaitingPermission=false,contactLimit=100,contactsLoading=false,contactError='';
  const CANDIDATE_PAGE_SIZE=20;
  let candidatePage=0,candidateDrafts=[];
  const state=()=>A.getState();
  function device(){try{return N&&N.deviceInfo?JSON.parse(N.deviceInfo()):{smsGranted:false,smsCapable:false,simReady:false};}catch(_){return {};}}
  function smsProblem(info=device()){
    if(!N||!info.smsCapable)return '문자 발송이 가능한 Android 휴대폰이 필요해요.';
    if(!info.smsGranted)return '문자 권한을 허용해 주세요.';
    if(!info.simReady)return '휴대폰 설정에서 기본 문자용 SIM을 선택해 주세요.';
    return '';
  }
  function smsSummary(){return state().settings.sms.enabled?(smsProblem()?'자동 발송 설정 켜짐 · 발송 준비 필요':'자동 발송 켜짐 · 발송 준비 완료'):'자동 발송 꺼짐 · 기본 문구 편집';}
  function refreshPermissions(info=device()){
    const target=$('#sms-permission-state'),actions=$('#sms-permission-actions');
    if(target)target.textContent=smsProblem(info)||'문자 권한 허용됨 · 기본 문자용 SIM 확인됨 · 발송 준비 완료';
    if(actions)actions.hidden=info.smsGranted===true;
    const summary=$('#sms-settings-summary');if(summary)summary.textContent=smsSummary();
    for(const button of document.querySelectorAll('[data-permission-needed=contacts]'))button.hidden=info.contactsGranted===true;
    if(contactAwaitingPermission&&info.contactsGranted&&contactMode){contactAwaitingPermission=false;requestContacts(contactMode);}
  }
  function guardianCard(g={},index=guardianSequence++){
    const role=g.relation||'guardian';
    return `<div class="guardian-card" data-guardian="${index}"><input type="hidden" name="g-id-${index}" value="${e(g.id||C.uid())}"><input type="hidden" name="g-contact-${index}" value="${e(g.contactId||'')}"><div class="row between"><select name="g-relation-${index}" aria-label="학부모 관계">${['mother','father','guardian'].map(r=>`<option value="${r}" ${r===role?'selected':''}>${relations[r]}</option>`).join('')}</select><button type="button" class="text-button small-button" data-contact-action="pick" data-target="guardian-${index}">${A.icon('users')} 연락처 선택</button><button type="button" class="icon-button" data-contact-action="remove-guardian" aria-label="학부모 연락처 삭제">${A.icon('close')}</button></div><label class="field"><span>학부모 이름</span><input name="g-name-${index}" maxlength="80" value="${e(g.name||'')}" placeholder="예: 김하늘 어머니"></label><label class="field"><span>학부모 전화번호</span><input name="g-phone-${index}" type="tel" inputmode="tel" maxlength="30" value="${e(g.phone||'')}" placeholder="010-0000-0000"></label><label class="toggle"><input name="g-notify-${index}" type="checkbox" ${g.notify!==false?'checked':''}>이 학부모에게 출석 문자 보내기</label></div>`;
  }
  function fields(s){
    guardianSequence=0;
    const list=s.guardians&&s.guardians.length?s.guardians:[{relation:'mother'},{relation:'father'}];
    return `<section class="contact-fields" data-edit-student="${e(s.id||'')}"><div class="section-title"><h3>학생 연락처</h3><button type="button" class="text-button" data-contact-action="pick" data-target="student">${A.icon('users')} 연락처 선택</button></div><input type="hidden" name="contactId" value="${e(s.contactId||'')}"><label class="field"><span>학생 전화번호 <small style="display:inline">선택</small></span><input name="phone" type="tel" inputmode="tel" maxlength="30" value="${e(s.phone||'')}" placeholder="학생 본인의 번호"></label><div class="section-title"><h3>학부모 연락처</h3><button type="button" class="text-button" data-contact-action="add-guardian">${A.icon('plus')} 추가</button></div><div id="guardian-fields">${list.map(g=>guardianCard(g)).join('')}</div><p class="hint">기존 학부모의 문자 수신 체크는 즉시 저장돼요. 새 연락처는 학생 정보 저장 후 등록돼요. ‘설정 → 출석 문자 설정’에서 자동 발송을 켜 주세요.</p></section>`;
  }
  function readFields(form){
    const fd=new FormData(form),raw=String(fd.get('phone')||''),phone=C.normalizePhone(raw);
    if(raw.trim()&&!phone)throw new Error('학생 전화번호를 확인해 주세요.');
    const guardians=[],seen=new Set();
    for(const card of form.querySelectorAll('[data-guardian]')){
      const i=card.dataset.guardian,name=String(fd.get('g-name-'+i)||'').trim(),raw=String(fd.get('g-phone-'+i)||'').trim();
      if(!name&&!raw)continue;
      const phone=C.normalizePhone(raw);if(!phone)throw new Error('학부모 전화번호를 확인해 주세요.');
      if(seen.has(phone))throw new Error('같은 학부모 번호가 두 번 입력됐어요.');seen.add(phone);
      const relation=String(fd.get('g-relation-'+i));
      guardians.push({id:String(fd.get('g-id-'+i)),name:name||relations[relation],relation,phone,notify:fd.has('g-notify-'+i),contactId:String(fd.get('g-contact-'+i)||'')});
    }
    return {phone,contactId:String(fd.get('contactId')||''),guardians};
  }
  function details(s){return `<div class="student-contacts"><p><b>학생</b> ${s.phone?e(s.phone):'번호 미등록'}</p>${s.guardians.length?s.guardians.map(g=>`<p><b>${relations[g.relation]}</b><span>${e(g.name)}<br><small>${e(g.phone)} · 문자 ${g.notify?'수신':'제외'}</small></span></p>`).join(''):'<p class="muted">학부모 연락처가 없어요. 정보 수정에서 추가하세요.</p>'}</div>`;}
  function studentTools(){return `<button class="contact-import-card" data-contact-action="auto-register"><span class="settings-icon">${A.icon('users')}</span><span><b>연락처에서 자동 등록</b><small>‘학생이름 어머니·아버지’로 학생과 학부모 찾기</small></span>${A.icon('right')}</button>`;}
  function settingsHTML(){
    const row=(action,im,title,sub)=>`<button class="settings-row" data-contact-action="${action}"><span class="settings-icon">${A.icon(im)}</span><span class="settings-label"><b>${title}</b><small>${sub}</small></span>${A.icon('right')}</button>`;
    return `<div class="settings-title">연락처와 출석 문자</div><div class="settings-card">${row('auto-register','users','연락처 자동 등록','이름 키워드로 학생·학부모 묶어 등록')}${row('keywords','search','연락처 인식 키워드','어머니·아버지·학생 등의 단어 수정')}${row('sms-settings','note','출석 문자 설정',`<span id="sms-settings-summary">${smsSummary()}</span>`)}${row('sms-log','clock','문자 발송 기록','최근 200건의 전송 결과 및 재전송')}</div>`;
  }
  function requestContacts(mode){
    if(!N||!N.loadContacts){A.toast('연락처 연동은 설치된 Android 앱에서 사용할 수 있어요.');return;}
    contactMode=mode;contactAwaitingPermission=false;scanToken=C.uid();
    contactLimit=100;contacts=[];contactsLoading=true;contactError='';
    const target=$('#contact-results')||$('#scan-state');if(target)target.innerHTML='<p class="hint">연락처를 불러오고 있어요…</p>';
    try{N.loadContacts(scanToken);}catch(_){onContacts({requestId:scanToken,ok:false,error:'연락처 요청을 전달하지 못했어요. 다시 불러와 주세요.'});}
  }
  function openPicker(target){
    const form=$('#modal-root form');if(!form)return;
    form.hidden=true;
    const panel=document.createElement('section');panel.className='contact-panel';panel.innerHTML=`<div class="row between"><h3>연락처 선택</h3><button type="button" class="text-button" data-contact-action="picker-back">돌아가기</button></div><label class="search-box">${A.icon('search')}<input id="contact-search" placeholder="이름 또는 전화번호 검색" aria-label="연락처 검색"></label><div id="contact-results"><p class="hint">연락처를 불러오고 있어요…</p></div>`;
    $('.modal').appendChild(panel);$('.modal').scrollTop=0;
    if(!N||!N.loadContacts){$('#contact-results').innerHTML='<p class="notice">연락처 연동은 Android 앱에서 사용할 수 있어요. 돌아가서 번호를 직접 입력할 수 있어요.</p>';return;}
    requestContacts({kind:'picker',target});
  }
  function closePicker(){const panel=$('.contact-panel');if(!panel)return false;panel.remove();const form=$('#modal-root form');if(form)form.hidden=false;contactMode=null;contactAwaitingPermission=false;contactsLoading=false;contactError='';contacts=[];scanToken='';return true;}
  function showContactList(query=''){
    const target=$('#contact-results');if(!target)return;
    if(contactsLoading){target.innerHTML='<p class="hint">연락처를 불러오고 있어요…</p>';return;}
    if(contactError){showContactError(target);return;}
    const q=query.trim().toLowerCase(),digits=query.replace(/\D/g,'');
    const list=contacts.map((c,index)=>({...c,index})).filter(c=>!q||c.name.toLowerCase().includes(q)||(digits&&c.phone.includes(digits)));
    target.innerHTML=list.length?`<p class="hint">${list.length}개 번호 · ${Math.min(contactLimit,list.length)}개 표시 중</p>${list.slice(0,contactLimit).map(c=>`<button type="button" class="settings-row" data-contact-action="contact-select" data-index="${c.index}"><span class="settings-label"><b>${e(c.name)}</b><small>${e(c.phone)}</small></span>${A.icon('right')}</button>`).join('')}${list.length>contactLimit?'<button type="button" class="secondary full" data-contact-action="contacts-more">연락처 더 보기</button>':''}`:'<p class="hint">일치하는 연락처가 없어요.</p>';
  }
  function selectContact(index){
    const c=contacts[index],mode=contactMode,form=$('#modal-root form');if(!c||!mode||!form)return;
    const parsed=C.parseContactName(c.name,state().settings.contactKeywords);
    if(mode.target==='student'){
      if(parsed&&parsed.role!=='student'){A.toast('학부모 연락처예요. 학부모 항목의 연락처 선택에서 연결해 주세요.');return;}
      form.elements.phone.value=c.phone;form.elements.contactId.value=c.id;
      if(!form.elements.name.value.trim())form.elements.name.value=(parsed?parsed.studentName:c.name).slice(0,30);
    }else{
      const i=mode.target.slice('guardian-'.length);form.elements['g-name-'+i].value=c.name;form.elements['g-phone-'+i].value=c.phone;form.elements['g-contact-'+i].value=c.id;
      if(parsed&&parsed.role!=='student')form.elements['g-relation-'+i].value=parsed.role;
    }
    closePicker();
  }
  function autoRegister(){
    A.modal('연락처에서 자동 등록',`<p class="modal-copy">김하늘 어머니 · 김하늘 아버지 · 김하늘 학생\n이름의 키워드를 찾아 가족 연락처를 묶어 드려요. 다음 화면에서 번호와 연결할 학생을 확인한 뒤 등록하세요.</p><div class="notice">연락처는 이 기기에서만 읽으며 주소록 자체는 수정하지 않아요.</div><button class="primary full" data-contact-action="scan">${A.icon('search')} 연락처에서 찾기</button><button class="text-button full" data-contact-action="keywords">인식 키워드 수정</button>`);
  }
  function showCandidates(){
    candidates=C.contactCandidates(contacts,state());candidatePage=0;
    candidateDrafts=candidates.map(c=>({use:c.matches.length<=1,name:c.name,target:c.matches.length===1?c.matches[0]:c.matches.length>1?'unresolved':'new',studentContact:c.studentContacts.length===1?'0':'none',guardians:c.guardians.map(()=>true),query:''}));
    A.modal('자동 등록 결과 확인',`<p class="modal-copy">${candidates.length}명의 학생 후보를 찾았어요. 이름·학부모 번호·기존 학생 연결을 확인하세요. 이번 작업으로 문자가 발송되지는 않아요.</p><form id="candidate-form"><p id="candidate-selection" class="hint" role="status"></p><div class="row"><button type="button" class="text-button" data-contact-action="candidates-all">전체 선택</button><button type="button" class="text-button" data-contact-action="candidates-none">전체 해제</button></div><div class="candidate-list"></div><div id="candidate-pager" class="row between"><button type="button" class="secondary small-button" data-contact-action="candidates-prev">이전 후보</button><span id="candidate-page" class="hint" role="status"></span><button type="button" class="secondary small-button" data-contact-action="candidates-next">다음 후보</button></div><div id="form-error" class="form-error" role="alert"></div><div class="modal-actions"><button type="button" class="secondary" data-action="close">취소</button><button class="primary" type="submit" ${candidates.length?'':'disabled'}>선택한 학생 등록</button></div></form>`,()=>{
      try{
        captureCandidates();
        const unresolved=candidateDrafts.findIndex(d=>d.use&&d.target==='unresolved');
        if(unresolved>=0){candidatePage=Math.floor(unresolved/CANDIDATE_PAGE_SIZE);renderCandidatePage();return A.formError(candidateDrafts[unresolved].name+' 학생의 연결 대상을 직접 선택해 주세요.');}
        const rows=candidates.flatMap((c,i)=>{const d=candidateDrafts[i];return d.use?[{name:d.name,targetId:d.target,studentContact:d.studentContact==='none'?null:c.studentContacts[Number(d.studentContact)],guardians:c.guardians.filter((_,j)=>d.guardians[j])}]:[];});
        if(!rows.length)return A.formError('등록할 학생을 하나 이상 선택해 주세요.');
        const result=C.mergeCandidates(state(),rows);
        if(A.save(result.state,`학생 ${result.added}명 등록 · ${result.updated}명 연락처 연결`))A.closeModal();
      }catch(error){A.formError(error.message);}
    });
    renderCandidatePage();
  }
  function captureCandidates(){
    const form=$('#candidate-form');if(!form)return;
    const fd=new FormData(form),start=candidatePage*CANDIDATE_PAGE_SIZE;
    for(let i=start;i<Math.min(start+CANDIDATE_PAGE_SIZE,candidates.length);i++){
      const d=candidateDrafts[i];d.use=fd.has('use-'+i);d.name=String(fd.get('name-'+i)||'');d.target=String(fd.get('target-'+i)||'unresolved');
      d.studentContact=String(fd.get('student-contact-'+i)||'none');d.query=String(fd.get('lookup-'+i)||'');d.guardians=candidates[i].guardians.map((_,j)=>fd.has(`guardian-${i}-${j}`));
    }
  }
  function candidateSelection(){
    const target=$('#candidate-selection');if(target)target.textContent=`전체 ${candidates.length}명 중 ${candidateDrafts.filter(d=>d.use).length}명 선택 · 다른 페이지의 선택도 함께 등록돼요.`;
  }
  function targetOptions(i,query=candidateDrafts[i].query,target=candidateDrafts[i].target){
    const c=candidates[i],needle=C.nameKey(query),digits=query.replace(/\D/g,''),students=state().students;
    const matches=students.map((s,index)=>({s,index})).filter(({s,index})=>needle?C.nameKey(s.name).includes(needle)||(digits&&s.phone.includes(digits))||query.trim()===`${index+1}번`:students.length<=30||c.matches.includes(s.id)||s.id===target);
    const list=matches.slice(0,30),selected=students.findIndex(s=>s.id===target);
    if(selected>=0&&!list.some(x=>x.s.id===target))list.unshift({s:students[selected],index:selected});
    const options=`<option value="unresolved" ${target==='unresolved'?'selected':''}>학생을 직접 선택해 주세요</option><option value="new" ${target==='new'?'selected':''}>새 학생으로 등록</option>`+list.map(({s,index})=>`<option value="${e(s.id)}" ${s.id===target?'selected':''}>${e(s.name)} · ${index+1}번${s.phone?' · 끝번호 '+e(s.phone.slice(-4)):''}${s.active?'':' · 보관'}</option>`).join('');
    const hint=matches.length>30?`${matches.length}명 중 처음 30명 표시 · 이름이나 번호를 더 입력해 주세요.`:needle?`검색 결과 ${matches.length}명 · 직접 선택해 주세요.`:'다른 학생은 이름·전화번호·학생 번호(예: 12번)로 찾아 연결하세요.';
    return {options,hint};
  }
  function candidateCard(c,i){
    const d=candidateDrafts[i],options=targetOptions(i);
    return `<div class="candidate-card" data-candidate="${i}"><label class="check-row"><input type="checkbox" name="use-${i}" ${d.use?'checked':''}><b>이 학생 등록·연결</b></label><label class="field"><span>자동으로 인식한 학생 이름</span><input name="name-${i}" maxlength="30" value="${e(d.name)}"><small>새 학생으로 등록할 때 사용할 이름이에요.</small></label><label class="field"><span>연결할 기존 학생 검색</span><input type="search" name="lookup-${i}" data-target-search="${i}" value="${e(d.query)}" placeholder="학생 이름 또는 전화번호" autocomplete="off"></label><label class="field"><span>연결할 학생</span><select name="target-${i}">${options.options}</select><small data-target-hint="${i}">${e(options.hint)}</small></label>${c.matches.length>1?'<p class="notice">동명이인이 있어요. 기존 학생 번호와 연락처를 확인해 직접 선택해 주세요.</p>':''}${c.studentContacts.length?`<label class="field"><span>학생 본인 연락처</span><select name="student-contact-${i}"><option value="none" ${d.studentContact==='none'?'selected':''}>연결하지 않음</option>${c.studentContacts.map((s,j)=>`<option value="${j}" ${d.studentContact===String(j)?'selected':''}>${e(s.name)} · ${e(s.phone)}</option>`).join('')}</select></label>`:''}<div class="guardian-candidates">${c.guardians.length?c.guardians.map((g,j)=>`<label class="check-row"><input type="checkbox" name="guardian-${i}-${j}" ${d.guardians[j]?'checked':''}><span><b>${e(g.name)}</b><small>${relations[g.relation]} · ${e(g.phone)}</small></span></label>`).join(''):'<p class="hint">인식한 학부모 번호가 없어요. 학생 정보에서 나중에 추가할 수 있어요.</p>'}</div></div>`;
  }
  function renderCandidatePage(){
    const list=$('#candidate-form .candidate-list');if(!list)return;
    const start=candidatePage*CANDIDATE_PAGE_SIZE,pages=Math.max(1,Math.ceil(candidates.length/CANDIDATE_PAGE_SIZE));
    list.innerHTML=candidates.length?candidates.slice(start,start+CANDIDATE_PAGE_SIZE).map((c,i)=>candidateCard(c,start+i)).join(''):'<p class="notice">인식된 이름이 없어요. 인식 키워드를 수정하거나 학생 등록에서 연락처를 직접 선택해 주세요.</p>';
    $('#candidate-page').textContent=`${candidatePage+1} / ${pages}페이지`;
    $('#candidate-pager').hidden=pages<=1;
    $('[data-contact-action=candidates-prev]').disabled=candidatePage===0;$('[data-contact-action=candidates-next]').disabled=candidatePage+1>=pages;
    candidateSelection();
  }
  function keywordsForm(){
    const keywords=state().settings.contactKeywords;
    A.modal('연락처 인식 키워드',`<form>${Object.keys(C.DEFAULT_KEYWORDS).map(role=>`<label class="field"><span>${relations[role]} 키워드</span><input name="${role}" maxlength="438" value="${e(keywords[role].join(', '))}"><small>쉼표로 구분해요. 이름 앞 또는 뒤의 단어를 인식해요.</small></label>`).join('')}<p class="hint">예: 김하늘 어머니 / 김하늘(어머니) / (김하늘)어머니<br>키워드가 없는 ‘김하늘’ 연락처는 인식된 학생 이름과 정확히 일치할 때 학생 번호 후보로 표시돼요.</p><div id="form-error" class="form-error" role="alert"></div><div class="modal-actions"><button type="button" class="secondary" data-action="close">취소</button><button type="submit" class="primary">키워드 저장</button></div></form>`,fd=>{
      const next=C.clone(state()),kw={},all=[];
      for(const key of Object.keys(C.DEFAULT_KEYWORDS)){kw[key]=String(fd.get(key)||'').split(',').map(s=>s.trim()).filter(Boolean);if(!kw[key].length||kw[key].length>20||kw[key].some(s=>s.length>20))return A.formError('각 항목은 1~20개, 단어마다 20자 이내로 입력해 주세요.');all.push(...kw[key]);}
      if(new Set(all).size!==all.length)return A.formError('관계마다 서로 다른 키워드를 사용해 주세요.');next.settings.contactKeywords=kw;
      if(A.save(next,'인식 키워드를 저장했어요.'))A.closeModal();
    });
  }
  function toggleSmsEnabled(input){
    const next=C.clone(state()),enabled=input.checked;
    // Persist the switch independently of unfinished template edits.
    if(enabled&&!next.settings.sms.statuses.length){
      input.checked=false;
      $('#sms-save-state').textContent='자동 발송 꺼짐 · 발송 대상을 먼저 선택해 주세요.';
      A.formError('문자를 보낼 출결 상태를 하나 이상 선택한 뒤 자동 발송을 켜 주세요.');
      return;
    }
    next.settings.sms.enabled=enabled;
    if(A.save(next,'')){
      $('#form-error').textContent='';$('#form-error').classList.remove('visible');
      $('#sms-save-state').textContent=enabled?'자동 발송 켜짐 · 저장됨':'자동 발송 꺼짐 · 저장됨';
      refreshPermissions();
    }else{
      input.checked=state().settings.sms.enabled;
      $('#sms-save-state').textContent='변경을 저장하지 못했어요. 기존 '+(input.checked?'켜짐':'꺼짐')+' 설정을 유지합니다.';
    }
  }
  function smsSettings(){
    const config=state().settings.sms,info=device();
    A.modal('출석 문자 설정',`<form id="sms-settings-form"><label class="sms-switch"><span><b>출석 체크 시 자동 발송</b><small>켜짐·꺼짐은 즉시 저장돼요</small></span><input id="sms-enabled" name="enabled" type="checkbox" aria-describedby="sms-save-state" ${config.enabled?'checked':''}></label><p id="sms-save-state" class="hint" role="status">자동 발송 ${config.enabled?'켜짐':'꺼짐'} · 저장된 설정</p><p class="hint">자동 발송과 발송 대상 체크는 즉시 저장돼요. 문구 편집은 아래 문구 저장 버튼으로 적용해 주세요.</p><div class="notice">휴대폰의 기본 SMS용 SIM으로 발송해요. 요금제에 따라 문자 요금이 발생하며, 긴 문구는 여러 SMS로 나뉠 수 있어요.</div><p class="hint">권한·SIM 준비 전에도 설정을 저장할 수 있어요. 준비가 끝난 뒤 새로 체크하는 출결부터 문자를 보내요.</p><p id="sms-permission-state" class="hint"></p><div id="sms-permission-actions" class="row" ${info.smsGranted?'hidden':''}><button type="button" class="secondary small-button" data-contact-action="sms-permission">문자 권한 허용</button><button type="button" class="text-button" data-contact-action="app-settings">앱 권한 설정</button></div><div class="field section-gap"><span>어떤 출결에 문자를 보낼까요?</span><div class="sms-status-options">${C.STATUSES.map(s=>`<label class="toggle"><input name="statuses" type="checkbox" value="${s}" ${config.statuses.includes(s)?'checked':''}>${C.LABELS[s]}</label>`).join('')}</div></div><label class="field"><span>문자 문구</span><textarea id="sms-template" name="template" maxlength="500" rows="5" required>${e(config.template)}</textarea></label><div class="token-list">${C.TOKENS.filter(t=>t!=='반이름').map(t=>`<button type="button" data-contact-action="token" data-token="${t}">{${t}}</button>`).join('')}</div><button class="text-button" type="button" data-contact-action="reset-template">기본 문구로 되돌리기</button><div class="sms-preview"><small>예시 미리보기 · 실제로 보내지 않아요</small><p id="sms-preview-text"></p></div><p class="hint">오늘 날짜의 출결 상태를 새로 체크하거나 변경할 때 발송해요. 메모만 수정·과거 기록 수정·미확인 복귀·되돌리기·백업 복원은 문자를 보내지 않아요. 같은 학생·날짜·상태·수신 번호로는 자동 중복 발송하지 않아요. 이미 발송된 문자는 되돌릴 수 없어요.</p><div id="form-error" class="form-error" role="alert"></div><div class="modal-actions"><button type="button" class="secondary" data-action="close">닫기</button><button class="primary" type="submit">문구 저장</button></div></form>`,fd=>{
      const enabled=fd.has('enabled'),info=device(),statuses=fd.getAll('statuses'),template=String(fd.get('template')||'').trim();
      if(enabled&&!statuses.length)return A.formError('문자를 보낼 출결 상태를 하나 이상 선택해 주세요.');if(!template)return A.formError('문자 문구를 입력해 주세요.');
      const unknown=[...template.matchAll(/\{([^{}]+)\}/g)].map(m=>m[1]).filter(t=>!C.TOKENS.includes(t));if(unknown.length)return A.formError('지원하지 않는 항목: '+unknown.join(', '));
      const next=C.clone(state());next.settings.sms={enabled,statuses,template};
      const pending=enabled?smsProblem(info):'';
      if(A.save(next,pending?'문자 설정을 저장했어요. '+pending:enabled?'문자 자동 발송을 켰어요.':'문자 설정을 저장했어요.'))A.closeModal();
    });$('#sms-enabled').addEventListener('change',ev=>toggleSmsEnabled(ev.target));refreshPermissions(info);updatePreview();
  }
  function toggleSmsStatus(input){
    const next=C.clone(state()),statuses=[...document.querySelectorAll('#sms-settings-form [name=statuses]:checked')].map(x=>x.value);
    next.settings.sms.statuses=statuses;
    if(!statuses.length)next.settings.sms.enabled=false;
    if(A.save(next,'')){
      $('#sms-enabled').checked=next.settings.sms.enabled;
      $('#sms-save-state').textContent=statuses.length?'발송 대상 · 저장됨':'발송 대상 없음 · 자동 발송을 껐어요';
      $('#form-error').textContent='';$('#form-error').classList.remove('visible');refreshPermissions();
    }else{
      input.checked=state().settings.sms.statuses.includes(input.value);
      $('#sms-save-state').textContent='저장하지 못해 기존 발송 대상을 유지합니다.';
    }
  }
  function toggleGuardian(input){
    const studentId=input.closest('[data-edit-student]')?.dataset.editStudent;
    const card=input.closest('[data-guardian]'),id=card?.querySelector('input[type=hidden]')?.value;
    const student=state().students.find(s=>s.id===studentId),guardian=student?.guardians.find(g=>g.id===id);
    if(!guardian)return; // A new student/contact is a draft until its form is saved.
    const next=C.clone(state());next.students.find(s=>s.id===studentId).guardians.find(g=>g.id===id).notify=input.checked;
    if(A.save(next,'학부모 문자 수신 설정을 저장했어요.')){
      $('#form-error')?.classList.remove('visible');
    }else input.checked=guardian.notify;
  }
  function updatePreview(){const input=$('#sms-template'),out=$('#sms-preview-text');if(!input||!out)return;out.textContent=C.renderSms(state(),{studentName:'김하늘',className:'전체 학생',date:C.localDate(),time:'16:00',status:'present',note:'',updatedAt:new Date().toISOString()},input.value);}
  function logsContent(){
    if(!N||!N.smsLogs)return '<p class="notice">문자 발송 기록은 Android 앱에서 확인할 수 있어요.</p>';
    let result;try{result=JSON.parse(N.smsLogs());}catch(_){return '<p class="notice">문자 기록을 읽지 못했어요.</p>';}
    if(!result.ok)return `<p class="notice">${e(result.error)}</p>`;
    if(!result.jobs.length)return '<p class="hint">아직 발송 기록이 없어요. 출석 문자 설정을 켜고 오늘 출석을 체크해 주세요.</p>';
    const labels={sending:'결과 대기',sent:'발송 완료',failed:'발송 실패',partial:'일부 발송 실패',unknown:'결과 미확인',cancelled:'발송 취소'};
    return result.jobs.map(j=>`<article class="sms-log-card"><div class="row between"><b>${e(j.studentName)} · ${C.LABELS[j.attendanceStatus]||''}</b><span class="sms-state ${e(j.state)}">${labels[j.state]||'확인 필요'}</span></div><p class="hint">${e(j.guardianName)} · ${e(j.phone)}<br>${e(new Date(j.createdAt).toLocaleString('ko-KR'))} · ${j.parts||0}개 SMS 분량${j.parts?` · 성공 ${j.sentParts||0} / 실패 ${j.failedParts||0} / 대기 ${j.pendingParts||0}`:''}</p><p class="sms-message">${e(j.message)}</p>${j.error?`<p class="${j.state==='failed'||j.state==='partial'?'form-error visible':'notice'}">${e(j.error)}</p>`:''}${['failed','partial','unknown'].includes(j.state)&&j.date===C.localDate()&&j.canRetry!==false?`<button class="text-button" data-contact-action="retry" data-id="${e(j.id)}">이 문자 다시 보내기</button>`:j.retryUnavailableReason&&j.state!=='sent'?`<p class="hint">${e(j.retryUnavailableReason)}</p>`:''}</article>`).join('');
  }
  function refreshLogs(){
    const target=$('#sms-logs');if(!target)return;
    const content=logsContent();
    // Keep text selection, scroll and keyboard focus when nothing changed.
    if(target._content===content)return;
    const modal=target.closest('.modal'),scroll=modal.scrollTop,focused=document.activeElement;
    const focusedId=focused?.dataset.contactAction==='retry'?focused.dataset.id:null;
    target.innerHTML=content;target._content=content;modal.scrollTop=scroll;
    if(focusedId){const button=[...target.querySelectorAll('[data-contact-action=retry]')].find(b=>b.dataset.id===focusedId);if(button)button.focus({preventScroll:true});}
  }
  function smsLog(){A.modal('문자 발송 기록',`<p class="hint">최근 200건 · ‘발송 완료’는 통신사 전송 완료 응답이며 학부모의 수신·읽음 확인은 아니에요. 결과가 없으면 자동 재전송하지 않아요.</p><button class="text-button" data-contact-action="refresh-logs">새로고침</button><div id="sms-logs"></div>`);refreshLogs();}
  function afterAttendanceSave(before,after){
    const changes=C.smsChanges(before,after);if(!changes.length)return;
    const problem=smsProblem();
    if(problem){A.smsToast('출석은 저장했어요. 문자는 보내지 않았어요. '+problem);return;}
    try{if(!N.sendAttendanceSms)throw new Error();N.sendAttendanceSms(JSON.stringify(changes));}
    catch(_){A.smsToast('출석은 저장했지만 문자 요청을 전달하지 못했어요. 문자 발송 기록을 확인해 주세요.');}
  }
  function showContactError(target){
    target.innerHTML=`<p class="notice">${e(contactError)}</p><button type="button" class="text-button" data-contact-action="app-settings" data-permission-needed="contacts" ${device().contactsGranted?'hidden':''}>앱 권한 설정 열기</button><button type="button" class="secondary small-button" data-contact-action="contacts-retry">다시 불러오기</button>`;
  }
  function onContacts(result){
    if(!contactMode||result.requestId!==scanToken)return;
    contactsLoading=false;
    if(!result.ok){contactError=result.error||'연락처를 읽지 못했어요.';contactAwaitingPermission=!device().contactsGranted;const scan=$('[data-contact-action=scan]');if(scan)scan.disabled=false;const target=$('#contact-results')||$('#scan-state');if(target)showContactError(target);else A.toast(contactError);return;}
    contactError='';
    contactAwaitingPermission=false;contacts=result.contacts||[];if(contactMode.kind==='picker')showContactList($('#contact-search')?.value||'');else{contactMode=null;scanToken='';showCandidates();}if(result.truncated)A.toast('연락처가 많아 처음 20,000개 번호를 표시했어요.');
  }
  function onPermission(info){refreshPermissions(info);}
  function onSmsQueue(result){if(!result.ok)A.smsToast(result.error||'문자를 요청하지 못했어요.');else if(result.message)A.smsToast(result.message);else A.smsToast(`문자 요청 ${result.queued||0}건 · 확인 필요 ${result.failed||0}건${result.skipped?' · 중복/제외 '+result.skipped+'건':''}. 발송 기록에서 확인하세요.`);refreshLogs();}
  document.addEventListener('click',ev=>{
    const b=ev.target.closest('[data-contact-action]');if(!b||b.disabled)return;
    const action=b.dataset.contactAction;
    if(action==='pick')openPicker(b.dataset.target);
    else if(action==='picker-back')closePicker();
    else if(action==='contacts-more'){
      const modal=$('.modal'),scroll=modal.scrollTop;contactLimit+=100;showContactList($('#contact-search')?.value||'');modal.scrollTop=scroll;
    }
    else if(action==='contacts-retry'&&contactMode){b.disabled=true;requestContacts(contactMode);}
    else if(action==='contact-select'&&!contactsLoading&&!contactError)selectContact(Number(b.dataset.index));
    else if(action==='candidates-next'||action==='candidates-prev'){captureCandidates();candidatePage+=action==='candidates-next'?1:-1;renderCandidatePage();$('.modal').scrollTop=0;}
    else if(action==='candidates-all'||action==='candidates-none'){captureCandidates();candidateDrafts.forEach(d=>d.use=action==='candidates-all');renderCandidatePage();}
    else if(action==='add-guardian'){const target=$('#guardian-fields');if(target.querySelectorAll('[data-guardian]').length>=6)return A.toast('학부모 연락처는 최대 6개까지 추가할 수 있어요.');target.insertAdjacentHTML('beforeend',guardianCard());}
    else if(action==='remove-guardian')b.closest('[data-guardian]').remove();
    else if(action==='auto-register')autoRegister();
    else if(action==='scan'){b.disabled=true;$('#scan-state')?.remove();const p=document.createElement('div');p.id='scan-state';p.innerHTML='<p class="hint">연락처에서 이름을 찾고 있어요…</p>';b.after(p);if(!N||!N.loadContacts){p.innerHTML='<p class="notice">연락처 연동은 Android 앱에서 사용할 수 있어요.</p>';b.disabled=false;}else requestContacts({kind:'scan'});}
    else if(action==='keywords')keywordsForm();
    else if(action==='sms-settings')smsSettings();
    else if(action==='sms-permission'){if(N&&N.requestSmsPermission)N.requestSmsPermission();else A.toast('Android 앱에서 권한을 허용해 주세요.');}
    else if(action==='app-settings'){if(N&&N.openAppSettings)N.openAppSettings();else A.toast('Android 앱에서 사용할 수 있어요.');}
    else if(action==='token'){const input=$('#sms-template');const token='{'+b.dataset.token+'}';const next=input.value.slice(0,input.selectionStart)+token+input.value.slice(input.selectionEnd);if(next.length>500)return A.toast('문구는 500자 이내로 작성해 주세요.');input.setRangeText(token,input.selectionStart,input.selectionEnd,'end');input.focus();updatePreview();}
    else if(action==='reset-template'){$('#sms-template').value=C.DEFAULT_TEMPLATE;updatePreview();}
    else if(action==='sms-log')smsLog();
    else if(action==='refresh-logs')refreshLogs();
    else if(action==='retry'){const problem=smsProblem();if(problem)return A.toast(problem);const id=b.dataset.id;A.confirmDialog('같은 문자를 다시 보낼까요?','일부 분할 문자가 이미 도착했을 수 있어요. 수신 여부를 확인한 경우에만 다시 보내 주세요. 추가 문자 요금이 발생할 수 있어요.','다시 보내기',()=>{N.retrySms(id);smsLog();});}
  });
  document.addEventListener('change',ev=>{if(ev.target.closest('#candidate-form')){captureCandidates();candidateSelection();}else if(ev.target.name==='statuses'&&ev.target.closest('#sms-settings-form'))toggleSmsStatus(ev.target);else if(ev.target.name?.startsWith('g-notify-'))toggleGuardian(ev.target);});
  document.addEventListener('input',ev=>{if(ev.target.id==='contact-search'){contactLimit=100;showContactList(ev.target.value);}if(ev.target.id==='sms-template')updatePreview();if(ev.target.dataset.targetSearch!==undefined){const i=Number(ev.target.dataset.targetSearch),select=$(`[name=target-${i}]`),options=targetOptions(i,ev.target.value,select.value);select.innerHTML=options.options;$(`[data-target-hint="${i}"]`).textContent=options.hint;}});
  document.addEventListener('keydown',ev=>{if(ev.key==='Enter'&&ev.target.dataset.targetSearch!==undefined){ev.preventDefault();$(`[name=target-${Number(ev.target.dataset.targetSearch)}]`).focus();}});
  setInterval(()=>{if(!document.hidden)refreshLogs();},3000);
  window.ContactSms={fields,readFields,details,studentTools,settingsHTML,afterAttendanceSave,onContacts,onPermission,onSmsQueue,onResume:()=>{refreshPermissions();refreshLogs();},handleBack:closePicker,onModalReplace:()=>{contactAwaitingPermission=false;contactMode=null;scanToken='';contactsLoading=false;contactError='';contacts=[];},onClose:()=>{contactAwaitingPermission=false;contactMode=null;scanToken='';contacts=[];candidates=[];candidateDrafts=[];contactsLoading=false;contactError='';}};
  A.render();
  if(N&&N.ready)N.ready();
})();
