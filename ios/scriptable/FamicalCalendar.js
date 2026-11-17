// Variables used by Scriptable.
// These must be at the very top of the file. Do not edit.
// icon-color: deep-green; icon-glyph: calendar;

// Famical iPhone home widget. Network requests go only to your configured NAS.
const PREFIX = "FamicalCalendar.v1.";
const load = (key, fallback=null) => Keychain.contains(PREFIX+key) ? JSON.parse(Keychain.get(PREFIX+key)) : fallback;
const save = (key,value) => Keychain.set(PREFIX+key,JSON.stringify(value));
const remove = key => { if(Keychain.contains(PREFIX+key)) Keychain.remove(PREFIX+key); };

function normalizeServer(input) {
  let value=String(input).trim();if(!value.includes("://"))value="https://"+value;
  const m=/^https:\/\/([a-zA-Z0-9.-]+|\[[a-fA-F0-9:]+\])(?::([0-9]{1,5}))?\/?$/i.exec(value);
  if(!m || (m[2] && (+m[2]<1 || +m[2]>65535)))throw Error("경로·아이디·비밀번호를 제외한 HTTPS 도메인을 입력하세요.");
  if(!m[1].startsWith("[") && !m[1].split(".").every(s=>/^[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?$/.test(s)))throw Error("도메인 형식을 확인해 주세요.");
  return "https://"+m[1].toLowerCase()+(m[2]&&+m[2]!==443?":"+Number(m[2]):"")+"/";
}
function stamp(date) { return `${date.getFullYear()}-${String(date.getMonth()+1).padStart(2,"0")}-${String(date.getDate()).padStart(2,"0")}`; }
function shiftFor(date,cfg) {
  if(!cfg.shiftOn || date.getDay()===0 || date.getDay()===6)return "";
  const weeks=Math.floor((Date.UTC(date.getFullYear(),date.getMonth(),date.getDate())-Date.UTC(2026,10,16))/604800000);
  let night=((weeks%2)+2)%2===0;if(cfg.team==="B")night=!night;
  return `${cfg.team||"A"}조 ${night?"야간":"주간"}`;
}
function visibleToday(events,date,cfg) {
  const day=stamp(date);
  return events.filter(e=>e.day<=day&&(e.end_day||e.day)>=day&&(cfg.includePrivate||e.visibility!=="private")).slice(0,3);
}
function monthEntries(events,cfg) {
  return events.filter(e=>cfg.includePrivate||e.visibility!=="private").map(e=>({day:e.day,end_day:e.end_day||e.day,color:e.color||"sage",visibility:e.visibility||"shared"}));
}
function eventColors(events,date,cfg) {
  const day=stamp(date),palette={blue:"3568a1",rose:"a74d6d",amber:"9d5c1d",violet:"7454a1",sage:"36705b"};
  return events.filter(e=>e.day<=day&&(e.end_day||e.day)>=day&&(cfg.includePrivate||e.visibility!=="private")).slice(0,3).map(e=>palette[e.color]||palette.sage);
}
function sessionCookie(response) {
  let value=(response.cookies||[]).find(c=>c.name==="session")?.value;
  if(!value) {
    const header=Object.entries(response.headers||{}).find(([k])=>k.toLowerCase()==="set-cookie")?.[1];
    value=String(header||"").match(/(?:^|[,;]\s*)session=([^;,\s]+)/)?.[1];
  }
  return value && /^[A-Za-z0-9._~-]+$/.test(value) ? "session="+value : null;
}
async function api(server,path,cookie,body,csrf) {
  const request=new Request(server+"api/"+path);
  request.method=body?"POST":"GET";request.timeoutInterval=12;
  request.allowInsecureRequest=false;request.onRedirect=()=>null;
  request.headers={Accept:"application/json",Cookie:cookie||""};
  if(body){request.headers["Content-Type"]="application/json";request.headers["X-CSRF-Token"]=csrf;request.body=JSON.stringify(body);}
  let data;try { data=await request.loadJSON(); } catch { throw Error("NAS API 연결을 확인해 주세요. HTTPS 인증서가 유효해야 합니다."); }
  return {data,status:request.response.statusCode,cookie:sessionCookie(request.response)};
}
async function loginInteractive() {
  const old=load("config",{}),alert=new Alert();alert.title="NAS 가족 달력 로그인";
  alert.message="달력이 열리는 HTTPS 도메인과 가족 계정을 입력하세요. 비밀번호는 저장하지 않습니다.";
  alert.addTextField("https://calendar.example.com",old.server||"");alert.addTextField("아이디",old.username||"");alert.addSecureTextField("비밀번호");
  alert.addAction("로그인");alert.addCancelAction("취소");if(await alert.presentAlert()<0)return false;
  const server=normalizeServer(alert.textFieldValue(0)),username=alert.textFieldValue(1).trim(),password=alert.textFieldValue(2);
  const initial=await api(server,"me",null);
  if(initial.status!==200||typeof initial.data.csrf!=="string"||!initial.cookie)throw Error("캘린더 API 주소가 아닙니다. DSM 관리 도메인과 구분해 주세요.");
  const logged=await api(server,"login",initial.cookie,{username,password},initial.data.csrf);
  if(logged.status!==200||!logged.data.ok||!logged.cookie)throw Error(logged.data.error||"로그인에 실패했습니다.");
  save("config",{server,username,team:old.team||"A",shiftOn:old.shiftOn!==false,includePrivate:false});
  save("cookie",logged.cookie);remove("cache");return true;
}
async function settingsMenu() {
  const cfg=load("config");if(!cfg)return;
  const alert=new Alert();alert.title="위젯 표시 설정";
  for(const label of ["A조 표시","B조 표시","교대 표시 OFF",cfg.includePrivate?"개인 일정 숨기기":"개인 일정도 표시하기"])alert.addAction(label);
  alert.addCancelAction("취소");const choice=await alert.presentSheet();
  if(choice<0)return;
  if(choice<2){cfg.team=choice===0?"A":"B";cfg.shiftOn=true;}
  if(choice===2)cfg.shiftOn=false;
  if(choice===3)cfg.includePrivate=!cfg.includePrivate;
  save("config",cfg);remove("cache");
}
async function readSnapshot(cfg,date) {
  const cookie=load("cookie");if(!cookie)return {events:[],message:"Scriptable에서 다시 로그인해 주세요",time:null};
  try {
    const response=await api(cfg.server,"events?month="+stamp(date).slice(0,7),cookie);
    if(response.status===401||response.status===403){remove("cookie");remove("cache");return {events:[],message:"로그인 만료 · 스크립트에서 재로그인",time:null};}
    if(response.status!==200||!Array.isArray(response.data))throw Error("Server response");
    const snapshot={day:stamp(date),events:visibleToday(response.data,date,cfg),monthEvents:monthEntries(response.data,cfg),message:"",time:date.toISOString()};
    save("cache",snapshot);return snapshot;
  } catch {
    const old=load("cache");
    return old&&old.day===stamp(date)?{...old,message:"연결 실패 · 마지막 갱신 내용"}:{events:[],message:"NAS 연결을 확인해 주세요",time:null};
  }
}
function label(stack,text,size,bold=false,color="285449") {
  const v=stack.addText(String(text));v.font=bold?Font.boldSystemFont(size):Font.systemFont(size);v.textColor=new Color(color);v.lineLimit=1;return v;
}
function monthGrid(parent,date,cfg,large,events) {
  const grid=parent.addStack();grid.layoutVertically();grid.spacing=1;
  const weekdays=["일","월","화","수","목","금","토"],width=large?35:16,height=large?24:13;
  const head=grid.addStack();head.spacing=1;
  weekdays.forEach(s=>{const cell=head.addStack();cell.size=new Size(width,10);const t=label(cell,s,large?10:8);t.centerAlignText();});
  const first=new Date(date.getFullYear(),date.getMonth(),1).getDay(),days=new Date(date.getFullYear(),date.getMonth()+1,0).getDate();
  for(let row=0;row<Math.ceil((first+days)/7);row++){
    const line=grid.addStack();line.spacing=1;
    for(let col=0;col<7;col++){
      const day=row*7+col-first+1,cell=line.addStack();cell.size=new Size(width,height);cell.cornerRadius=3;
      if(day<1||day>days)continue;
      const current=new Date(date.getFullYear(),date.getMonth(),day),shift=shiftFor(current,cfg);
      cell.backgroundColor=new Color(col===0||col===6?"f3dfe1":shift.endsWith("야간")?"f7efbb":shift?"dce9d8":"ffffff");
      if(day===date.getDate()){cell.borderWidth=1;cell.borderColor=new Color("E53935");}
      cell.layoutVertically();const number=cell.addStack();number.addSpacer();
      const t=label(number,day,large?12:8,day===date.getDate());t.centerAlignText();number.addSpacer();
      const colors=eventColors(events,current,cfg);if(colors.length){const bars=cell.addStack();bars.spacing=1;for(const color of colors){const bar=bars.addStack();bar.size=new Size((width-(colors.length-1))/colors.length,2);bar.backgroundColor=new Color(color);}}
    }
  }
}
function eventList(parent,snapshot,limit) {
  const list=parent.addStack();list.layoutVertically();list.spacing=5;
  if(!snapshot.events.length){const t=label(list,snapshot.message||"오늘 표시할 일정이 없습니다",11);t.lineLimit=3;}
  for(const event of snapshot.events.slice(0,limit)){
    const row=list.addStack();row.spacing=5;
    const bar=row.addStack();bar.size=new Size(3,27);bar.cornerRadius=2;
    bar.backgroundColor=new Color(({blue:"3568a1",rose:"a74d6d",amber:"9d5c1d",violet:"7454a1"})[event.color]||"36705b");
    const text=row.addStack();text.layoutVertically();label(text,event.clock||"종일",9,false,"65705a");label(text,event.title,12,true);
  }
}
function buildWidget(cfg,snapshot,date,family) {
  const widget=new ListWidget();widget.backgroundColor=new Color("f6f5ef");widget.setPadding(10,10,10,10);widget.spacing=4;
  widget.refreshAfterDate=new Date(date.getTime()+60*60*1000);if(cfg.server)widget.url=cfg.server;
  const heading=widget.addStack();heading.spacing=5;
  label(heading,`${date.getMonth()+1}월 ${date.getDate()}일 ${["일","월","화","수","목","금","토"][date.getDay()]}요일`,15,true);
  const shift=shiftFor(date,cfg);if(shift){heading.addSpacer();label(heading,shift,10);}
  if(family==="small")eventList(widget,snapshot,2);
  else if(family==="medium") {const body=widget.addStack();body.spacing=12;monthGrid(body,date,cfg,false,snapshot.monthEvents||snapshot.events);eventList(body,snapshot,2);}
  else {monthGrid(widget,date,cfg,true,snapshot.monthEvents||snapshot.events);eventList(widget,snapshot,3);}
  widget.addSpacer();
  const time=snapshot.time?new Date(snapshot.time):null;
  label(widget,snapshot.message||(time?`${String(time.getHours()).padStart(2,"0")}:${String(time.getMinutes()).padStart(2,"0")} 갱신` :"Scriptable에서 로그인해 주세요"),9,false,"65705a");
  return widget;
}
async function runScript() {
  let cfg=load("config");
  try {
    if(!config.runsInWidget) {
      if(!cfg){if(!await loginInteractive())return;}
      else {
        const menu=new Alert();menu.title="우리 가족 달력";
        for(const action of ["위젯 미리보기·갱신","달력 열기","로그인·서버 변경","위젯 표시 설정","연결 해제"])menu.addAction(action);
        menu.addCancelAction("취소");const choice=await menu.presentSheet();if(choice<0)return;
        if(choice===1){Safari.open(cfg.server);return;}
        if(choice===2&&!await loginInteractive())return;
        if(choice===3)await settingsMenu();
        if(choice===4){remove("cookie");remove("cache");remove("config");cfg=null;}
      }
    }
    cfg=load("config",{team:"A",shiftOn:true,includePrivate:false});
    const date=new Date(),snapshot=cfg.server?await readSnapshot(cfg,date):{events:[],message:"Scriptable에서 스크립트를 실행해 로그인하세요",time:null};
    const family=config.runsInWidget?config.widgetFamily:"large",widget=buildWidget(cfg,snapshot,date,family);
    Script.setWidget(widget);if(!config.runsInWidget)await widget.presentLarge();
  } catch(e) {
    if(config.runsInWidget){const w=new ListWidget();label(w,"캘린더 연결을 확인해 주세요",14,true);Script.setWidget(w);}
    else{const a=new Alert();a.title="설정 확인";a.message=e.message||"연결을 확인해 주세요";a.addAction("확인");await a.presentAlert();}
  } finally { Script.complete(); }
}

// SCRIPTABLE_ENTRY_POINT
await runScript();
