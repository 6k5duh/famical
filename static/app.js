import {layoutWeek, lunarLabel} from './calendar-layout.mjs?v=1.2.0';
const $ = id => document.getElementById(id);
let csrf = '', me = null, events = [], lunar = {}, cursor = new Date(), requestNo = 0;
const key = 'familyCalendar.shift';
let prefs = {on:true, team:'A'};
try { prefs = {...prefs, ...JSON.parse(localStorage.getItem(key)||'{}')}; } catch {}
$('shiftOn').checked = prefs.on;
$('team').value = prefs.team;
const iso = d => `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;
async function api(path, method='GET', body) {
  const r = await fetch('/api/'+path, {method, credentials:'same-origin',
    headers:{'Content-Type':'application/json','X-CSRF-Token':csrf},
    ...(body ? {body:JSON.stringify(body)} : {})});
  const d = await r.json();
  if (!r.ok) {
    if (r.status===401 && path!=='login') { me=null; show(); }
    throw Error(d.error || '요청에 실패했습니다.');
  }
  return d;
}
function say(s) { $('message').textContent=s; }
function show() {
  $('membersButton').hidden=!me?.admin;
  $('login').hidden=!!me; $('calendar').hidden=!me;
  $('who').textContent=me ? `${me.name} 님` : '';
  if (!me) { events=[]; $('grid').replaceChildren(); }
}
async function boot() {
  try { const d=await api('me'); csrf=d.csrf; me=d.user; show(); if(me) await load(); }
  catch(e) { say(e.message); }
}
async function load() {
  if (!me) return;
  const n=++requestNo, month=iso(cursor).slice(0,7);
  try {
    const [result,moon]=await Promise.all([api('events?month='+month),api('lunar?month='+month)]);
    if (n!==requestNo || !me) return;
    events=result; lunar=moon; render();
    $('sync').textContent=`${new Date().toLocaleTimeString('ko-KR',{hour:'2-digit',minute:'2-digit'})} 동기화`; say('');
  } catch(e) { say(e.message); }
}
function shift(d) {
  if (!prefs.on || d.getDay()===0 || d.getDay()===6) return '';
  const weeks=Math.floor((Date.UTC(d.getFullYear(),d.getMonth(),d.getDate())-Date.UTC(2026,10,16))/604800000);
  const aNight=((weeks%2)+2)%2===0;
  return (prefs.team==='A' ? aNight : !aNight) ? 'night' : 'day';
}
function render() {
  $('monthTitle').textContent=`${cursor.getFullYear()}년 ${cursor.getMonth()+1}월`;
  $('legend').hidden=!prefs.on; $('team').disabled=!prefs.on;
  const grid=$('grid'); grid.replaceChildren();
  const y=cursor.getFullYear(), m=cursor.getMonth(), offset=new Date(y,m,1).getDay(), last=new Date(y,m+1,0).getDate();
  const visible=events.filter(e=>$('eventView').value!=='mine'||e.owner===me.id);
  for (let base=0; base<offset+last; base+=7) {
    const dates=Array.from({length:7},(_,col)=>{const day=base+col-offset+1; return day>=1&&day<=last ? iso(new Date(y,m,day)) : null;});
    const segments=layoutWeek(visible,dates);
    const lanes=segments.length ? Math.max(...segments.map(s=>s.lane))+1 : 0;
    const week=document.createElement('div'); week.className='calendar-week';
    week.style.gridTemplateRows=`56px ${lanes ? `repeat(${lanes}, 30px) ` : ''}minmax(28px, 1fr)`;
    for(let col=0; col<7; col++) {
      const stamp=dates[col], cell=document.createElement('div'); cell.className='cell';
      cell.style.gridColumn=String(col+1); cell.style.gridRow=`1 / span ${lanes+2}`;
      if(!stamp) { cell.classList.add('blank'); week.append(cell); continue; }
      const day=Number(stamp.slice(-2)), d=new Date(y,m,day), s=shift(d);
      if([0,6].includes(col)) cell.classList.add('weekend'); if(s) cell.classList.add(s);
      if(stamp===iso(new Date())) cell.classList.add('today');
      const heading=document.createElement('div'); heading.className='date-heading';
      const num=document.createElement('span'); num.className='date'; num.textContent=day; heading.append(num);
      if(lunar[stamp]) { const moon=document.createElement('small'); moon.className='lunar'; moon.textContent=lunarLabel(lunar[stamp]); heading.append(moon); }
      cell.append(heading);
      if(s) { const tag=document.createElement('span'); tag.className='shift'; tag.textContent=`${prefs.team} ${s==='night'?'야간':'주간'}`; cell.append(tag); }
      cell.tabIndex=0; cell.setAttribute('role','button'); cell.setAttribute('aria-label',`${m+1}월 ${day}일 일정 추가`);
      cell.onclick=()=>edit({day:stamp});
      cell.onkeydown=e=>{if(e.target===cell&&(e.key==='Enter'||e.key===' ')){e.preventDefault();edit({day:stamp});}};
      week.append(cell);
    }
    for(const segment of segments) {
      const ev=segment.event, b=document.createElement('button');
      b.className=`event-bar color-${ev.color}${segment.continuesBefore?' continues-before':''}${segment.continuesAfter?' continues-after':''}`;
      b.style.gridColumn=`${segment.startColumn+1} / ${segment.endColumn+2}`;
      b.style.gridRow=String(segment.lane+2);
      b.textContent=`${segment.continuesBefore?'‹ ':''}${ev.visibility==='private'?'🔒 ':''}${ev.clock||''} ${ev.title}${ev.annual?' ↻':''}${segment.continuesAfter?' ›':''}`.trim();
      b.title=`${ev.title} · ${ev.day} ~ ${ev.end_day} · ${ev.author}${ev.visibility==='private'?' · 나에게만':''}`;
      b.setAttribute('aria-label',b.title); b.onclick=()=>edit(ev); week.append(b);
    }
    grid.append(week);
  }
}
function dateHelp() {
  const moon=$('calendarType').value==='lunar'; $('leapLabel').hidden=!moon;
  $('dateHelp').textContent=moon
    ? '시작일·종료일 모두 음력 YYYY-MM-DD로 입력하세요 (1900~2050년). 윤달 여부는 두 날짜에 함께 적용됩니다. 반복 시 날짜가 없는 해는 건너뜁니다.'
    : '양력 YYYY-MM-DD로 입력하세요. 종료일까지 포함하며 최대 366일입니다. 매년 반복 시 2월 29일은 윤년에만 표시됩니다.';
}
function edit(ev) {
  const f=$('eventForm'); f.reset(); $('editorError').textContent='';
  for(const field of ['id','title','day','clock','note']) f.elements[field].value=ev[field]||'';
  f.elements.day.value=ev.source_day||ev.day||'';
  f.elements.end_day.value=ev.source_end_day||ev.end_day||ev.day||'';
  f.elements.calendar_type.value=ev.calendar_type||'solar'; f.elements.color.value=ev.color||'sage';
  f.elements.visibility.value=ev.visibility||'shared';
  f.elements.annual.checked=!!ev.annual; f.elements.leap.checked=!!ev.leap; dateHelp();
  const allowed=!ev.id||me.admin||ev.owner===me.id;
  for(const field of ['title','day','end_day','clock','note','calendar_type','color','annual','leap']) f.elements[field].disabled=!allowed;
  for(const radio of f.querySelectorAll('[name=visibility]')) radio.disabled=!allowed;
  $('save').hidden=!allowed; $('remove').hidden=!ev.id||!allowed;
  $('author').textContent=ev.author?`작성: ${ev.author}${allowed?'':' · 조회만 가능'}`:'공개 범위 기본값은 모두에게 보이기입니다.';
  $('editor').showModal();
}
$('loginForm').onsubmit=async e=>{e.preventDefault();try{await api('login','POST',Object.fromEntries(new FormData(e.target)));e.target.reset();say('');await boot();}catch(e){say(e.message);}};
$('logout').onclick=async()=>{try{await api('logout','POST');await boot();}catch(e){say(e.message);}};
$('eventForm').onsubmit=async e=>{
  e.preventDefault(); const d=Object.fromEntries(new FormData(e.target));
  d.annual=e.target.elements.annual.checked; d.leap=d.calendar_type==='lunar'&&e.target.elements.leap.checked;
  $('save').disabled=true;
  try { await api('events'+(d.id?'/'+d.id:''),d.id?'PUT':'POST',d); $('editor').close(); await load(); }
  catch(e){$('editorError').textContent=e.message;} finally{$('save').disabled=false;}
};
$('remove').onclick=async()=>{if(!confirm('이 일정 전체를 삭제할까요?'))return;try{await api('events/'+$('eventForm').elements.id.value,'DELETE');$('editor').close();await load();}catch(e){$('editorError').textContent=e.message;}};
$('close').onclick=()=>$('editor').close(); $('add').onclick=()=>edit({day:iso(new Date())});
$('calendarType').onchange=dateHelp; $('eventView').onchange=render;
$('prev').onclick=()=>{if(cursor.getFullYear()===1900&&cursor.getMonth()===0)return;cursor=new Date(cursor.getFullYear(),cursor.getMonth()-1,1);load();};
$('next').onclick=()=>{if(cursor.getFullYear()===2099&&cursor.getMonth()===11)return;cursor=new Date(cursor.getFullYear(),cursor.getMonth()+1,1);load();};
$('today').onclick=()=>{cursor=new Date();load();}; $('refresh').onclick=load;
for(const id of ['shiftOn','team']) $(id).onchange=()=>{prefs={on:$('shiftOn').checked,team:$('team').value};try{localStorage.setItem(key,JSON.stringify(prefs));}catch{}render();};
$('passwordButton').onclick=()=>{$('passwordForm').reset();$('passwordError').textContent='';$('passwordDialog').showModal();};
$('passwordClose').onclick=()=>$('passwordDialog').close();
$('passwordForm').onsubmit=async e=>{e.preventDefault();try{await api('password','POST',Object.fromEntries(new FormData(e.target)));$('passwordDialog').close();await boot();say('비밀번호를 변경했습니다. 다시 로그인해 주세요.');}catch(e){$('passwordError').textContent=e.message;}};
document.addEventListener('visibilitychange',()=>{if(!document.hidden&&me)load();});
setInterval(()=>{if(!document.hidden&&me&&!$('editor').open)load();},30000);
if('serviceWorker'in navigator) navigator.serviceWorker.register('/sw.js').catch(()=>{});
boot();

async function loadMembers() {
  const rows=await api('users');
  $('membersList').replaceChildren();
  for(const u of rows) {
    const item=document.createElement('p');
    item.textContent=`${u.name} (${u.username}) · ${u.admin?'관리자':'가족'}`;
    $('membersList').append(item);
  }
}
$('membersButton').onclick=async()=>{
  $('memberForm').reset(); $('memberError').textContent='';
  try{await loadMembers();$('membersDialog').showModal();}catch(e){say(e.message);}
};
$('membersClose').onclick=()=>$('membersDialog').close();
$('memberForm').onsubmit=async e=>{
  e.preventDefault();$('memberSave').disabled=true;
  const d=Object.fromEntries(new FormData(e.target));d.admin=e.target.elements.admin.checked;
  try{await api('users','POST',d);e.target.reset();$('memberError').textContent='계정을 추가했습니다.';await loadMembers();}
  catch(e){$('memberError').textContent=e.message;}finally{$('memberSave').disabled=false;}
};
